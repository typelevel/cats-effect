/*
 * Copyright 2020-2025 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats.effect.kernel
package testkit

import cats.{~>, Defer, Eq, Functor, Id, Monad, MonadError, Order, Show}
import cats.data.{Kleisli, State, WriterT}
import cats.effect.kernel._
import cats.free.FreeT
import cats.syntax.all._

import coop.{ApplicativeThread, MVar, ThreadT}

object pure {

  type IdOC[E, A] = Outcome[Id, E, A] // a fiber may complete, error, or cancel
  type FiberR[E, A] = Kleisli[IdOC[E, *], FiberCtx[E], A] // fiber context and results
  type MVarR[F[_], A] = Kleisli[F, MVar.Universe, A] // ability to use MVar(s)

  type PureConc[E, A] = MVarR[ThreadT[FiberR[E, *], *], A]

  type Finalizer[E] = PureConc[E, Unit]

  final class MaskId

  object MaskId {
    implicit val eq: Eq[MaskId] = Eq.fromUniversalEquals[MaskId]
  }

  private[pure] final case class MaskFrame(id: MaskId)

  private[pure] final class FinalizerId

  private[pure] final case class RegisteredFinalizer[E](id: FinalizerId, action: Finalizer[E])

  // These are the purely functional analogue of IOFiber's mask and finalizer stacks.
  private[pure] final case class FiberState[E](
      frames: List[MaskFrame],
      activePolls: Int,
      finalizers: List[RegisteredFinalizer[E]])

  private[pure] final class CancelationListenerId

  private[pure] object CancelationListenerId {
    implicit val eq: Eq[CancelationListenerId] =
      Eq.fromUniversalEquals[CancelationListenerId]
  }

  private[pure] final case class CancelationListener[E](
      id: CancelationListenerId,
      action: PureConc[E, Unit])

  private sealed trait MaskUpdate

  private object MaskUpdate {
    case object Removed extends MaskUpdate
    case object Shadowed extends MaskUpdate
    case object Absent extends MaskUpdate
  }

  sealed case class FiberCtx[E](
      self: PureFiber[E, ?],
      masks: List[MaskId] = Nil,
      finalizers: List[PureConc[E, Unit]] = Nil) {

    private[pure] def finalizing: Boolean = false

    private[pure] def withFinalizers(value: List[PureConc[E, Unit]]): FiberCtx[E] =
      FiberCtx.internal(self, masks, value, finalizing)

    private[pure] def withFinalizing(value: Boolean): FiberCtx[E] =
      FiberCtx.internal(self, masks, finalizers, value)
  }

  object FiberCtx {
    private final class Internal[E](
        self: PureFiber[E, ?],
        masks: List[MaskId],
        finalizers: List[PureConc[E, Unit]],
        override private[pure] val finalizing: Boolean)
        extends FiberCtx[E](self, masks, finalizers)

    private[pure] def internal[E](
        self: PureFiber[E, ?],
        masks: List[MaskId],
        finalizers: List[PureConc[E, Unit]],
        finalizing: Boolean): FiberCtx[E] =
      new Internal(self, masks, finalizers, finalizing)
  }

  type ResolvedPC[E, A] = ThreadT[IdOC[E, *], A]

  // this is to hand-hold scala 2.12 a bit
  implicit def monadErrorIdOC[E]: MonadError[IdOC[E, *], E] =
    Outcome.monadError[Id, E]

  def resolveMain[E, A](pc: PureConc[E, A]): ResolvedPC[E, IdOC[E, A]] = {
    val M = rawMonad[E]

    /*
     * The failures of type inference make this look HORRIBLE but the general idea is fairly
     * simple: mapK over the FreeT into a new monad. Thus, we go from Kleisli[FreeT[Kleisli[Outcome[Id, ...]]]]
     * to Kleisli[FreeT[Kleisli[FreeT[Kleisli[Outcome[Id, ...]]]]]]]], which we then need to go
     * through and flatten. The flattening process is in the definition of `val canceled`.
     *
     * FlatMapK and TraverseK typeclasses would make this a one-liner.
     */

    val cancelationCheck = new (FiberR[E, *] ~> PureConc[E, *]) {
      def apply[α](ka: FiberR[E, α]): PureConc[E, α] = {
        val back = Kleisli.ask[IdOC[E, *], FiberCtx[E]] map { ctx =>
          val checker =
            M.flatMap(ctx.self.hasActivePoll) { active =>
              if (active) M.unit
              else
                M.flatMap(ctx.self.isFinalizing) { finalizing =>
                  if (finalizing) M.unit
                  else
                    M.flatMap(ctx.self.realizeCancelationAtEvaluatorBoundaryWith(ctx)) {
                      canceled =>
                        if (canceled) ApplicativeThread[PureConc[E, *]].done[Unit]
                        else M.unit
                    }
                }
            }

          M.productR(checker)(mvarLiftF(ThreadT.liftF(ka)))
        }

        M.flatten(mvarLiftF(ThreadT.liftF(back)))
      }
    }

    // flatMapF does something different
    val canceled = Kleisli { (u: MVar.Universe) =>
      val outerStripped = pc.mapF(_.mapK(cancelationCheck)).run(u) // run the outer mvar kleisli

      val traversed = outerStripped mapK { // run the inner mvar kleisli
        new (PureConc[E, *] ~> ThreadT[FiberR[E, *], *]) {
          def apply[a](fa: PureConc[E, a]) = fa.run(u)
        }
      }

      flattenK(traversed)
    }

    val backM = MVar.resolve {
      // this is PureConc[E, *] without the inner Kleisli
      type Main[X] = MVarR[ResolvedPC[E, *], X]

      MVar.empty[Main, Outcome[PureConc[E, *], E, A]].flatMap { state0 =>
        MVar.empty[Main, Unit] flatMap { canceled0 =>
          MVar[Main, FiberState[E]](FiberState(Nil, 0, Nil)) flatMap { fiberState =>
            MVar[Main, List[CancelationListener[E]]](Nil) flatMap { cancelationListeners =>
              MVar[Main, Boolean](false) flatMap { finalizing =>
                val state = state0[Main]
                val fiber =
                  new PureFiber[E, A](
                    state0,
                    canceled0,
                    fiberState,
                    cancelationListeners,
                    finalizing)

                val completed = M.flatMap(canceled) { a =>
                  withCtx { ctx =>
                    M.flatMap(ctx.self.realizeCancelationWith(ctx)) { canceled =>
                      if (canceled) ApplicativeThread[PureConc[E, *]].done[A]
                      else M.pure(a)
                    }
                  }
                }

                val identified = completed mapF { ta =>
                  val fk = new (FiberR[E, *] ~> IdOC[E, *]) {
                    def apply[a](ke: FiberR[E, a]) =
                      ke.run(FiberCtx(fiber))
                  }

                  ta.mapK(fk)
                }

                import Outcome._

                val body = identified flatMap { a =>
                  state.tryPut(Succeeded(M.pure(a)))
                } handleErrorWith { e => state.tryPut(Errored(e)) }

                val results = state.read.flatMap {
                  case Canceled() => (Outcome.Canceled(): IdOC[E, A]).pure[Main]
                  case Errored(e) => (Outcome.Errored(e): IdOC[E, A]).pure[Main]

                  case Succeeded(fa) =>
                    val identifiedCompletion = fa.mapF { ta =>
                      val fk = new (FiberR[E, *] ~> IdOC[E, *]) {
                        def apply[a](ke: FiberR[E, a]) =
                          ke.run(FiberCtx(fiber))
                      }

                      ta.mapK(fk)
                    }

                    identifiedCompletion.map(a =>
                      Succeeded[Id, E, A](a): IdOC[E, A]) handleError { e => Errored(e) }
                }

                Kleisli.ask[ResolvedPC[E, *], MVar.Universe].map { u =>
                  ApplicativeThread[ResolvedPC[E, *]].start(body.run(u)) >> results.run(u)
                }
              }
            }
          }
        }
      }
    }

    backM.flatten
  }

  /**
   * Produces Succeeded(None) when the main fiber is deadlocked. Note that deadlocks outside of
   * the main fiber are ignored when results are appropriately produced (i.e. daemon semantics).
   */
  def run[E, A](pc: PureConc[E, A]): Outcome[Option, E, A] = {
    val scheduled = ThreadT.roundRobin {
      // we put things into WriterT because roundRobin returns Unit
      resolveMain(pc).mapK(WriterT.liftK[IdOC[E, *], List[IdOC[E, A]]]).flatMap { ec =>
        ThreadT.liftF {
          WriterT.tell[IdOC[E, *], List[IdOC[E, A]]](List(ec))
        }
      }
    }

    val optLift = new (Id ~> Option) {
      def apply[a](a: a) = Some(a)
    }

    scheduled.run.mapK(optLift).flatMap {
      case (List(results), _) => results.mapK(optLift)
      case (_, false) => Outcome.Succeeded(None)

      // in the case of never and such, we are awaiting the async cancel monitor
      // this scenario only arises if the main fiber self cancels
      case _ => Outcome.Canceled()
    }
  }

  implicit def orderForPureConc[E: Order, A: Order]: Order[PureConc[E, A]] =
    Order.by(pure.run(_))

  implicit def allocateForPureConc[E]: GenConcurrent[PureConc[E, *], E] =
    new GenConcurrent[PureConc[E, *], E] {
      private[this] val M: MonadError[PureConc[E, *], E] = rawMonad[E]

      private[this] val Thread = ApplicativeThread[PureConc[E, *]]
      private[this] val Ask = implicitly[MVar.Ask[PureConc[E, *]]]

      private[this] def emptyMVar[A]: PureConc[E, MVar[A]] =
        MVar.empty[PureConc[E, *], A](M, Thread)

      private[this] def mvar[A](a: A): PureConc[E, MVar[A]] =
        MVar[PureConc[E, *], A](a)(M, Thread, Ask)

      private[this] def readMVar[A](mvar: MVar[A]): PureConc[E, A] =
        mvar.read[PureConc[E, *]](M, Thread, Ask)

      private[this] def tryReadMVar[A](mvar: MVar[A]): PureConc[E, Option[A]] =
        mvar.tryRead[PureConc[E, *]](M, Ask)

      private[this] def tryPutMVar[A](mvar: MVar[A], a: A): PureConc[E, Boolean] =
        mvar.tryPut[PureConc[E, *]](a)(M, Thread, Ask)

      private[this] def putMVar[A](mvar: MVar[A], a: A): PureConc[E, Unit] =
        mvar.put[PureConc[E, *]](a)(M, Thread, Ask)

      private[this] def takeMVar[A](mvar: MVar[A]): PureConc[E, A] =
        mvar.take[PureConc[E, *]](M, Thread, Ask)

      private[this] def swapMVar[A](mvar: MVar[A], a: A): PureConc[E, A] =
        mvar.swap[PureConc[E, *]](a)(M, Thread, Ask)

      private[this] def cancelationBoundaryWith[A](
          ctx: FiberCtx[E],
          fa: => PureConc[E, A]): PureConc[E, A] =
        M.flatMap(ctx.self.realizeCancelationWith(ctx)) { canceled =>
          if (canceled) Thread.done[A] else fa
        }

      private[this] def cancelationBoundary[A](fa: => PureConc[E, A]): PureConc[E, A] =
        withCtx(ctx => cancelationBoundaryWith(ctx, fa))

      private[this] def onCancelWith[A](
          fa: PureConc[E, A],
          fin: PureConc[E, Unit]): PureConc[E, A] =
        withCtx[E, A] { ctx =>
          // Frame unwinding is structural, so it must not introduce user flatMap boundaries.
          M.flatMap(ctx.self.registerFinalizer(M.void(M.attempt(fin)))) { id =>
            M.flatMap(M.attempt(fa)) { result =>
              M.flatMap(ctx.self.removeFinalizer(id))(_ => M.rethrow(M.pure(result)))
            }
          }
        }

      def pure[A](x: A): PureConc[E, A] =
        M.pure(x)

      def handleErrorWith[A](fa: PureConc[E, A])(f: E => PureConc[E, A]): PureConc[E, A] =
        Thread.annotate("handleErrorWith", true)(
          M.handleErrorWith(fa)(e => cancelationBoundary(f(e))))

      def raiseError[A](e: E): PureConc[E, A] =
        Thread.annotate("raiseError")(M.raiseError(e))

      def onCancel[A](fa: PureConc[E, A], fin: PureConc[E, Unit]): PureConc[E, A] =
        Thread.annotate("onCancel", true)(onCancelWith(fa, fin))

      def canceled: PureConc[E, Unit] =
        Thread.annotate("canceled")(withCtx { ctx =>
          M.flatMap(ctx.self.cancelAndRealizeWith(ctx)) { canceled =>
            if (canceled) Thread.done else M.unit
          }
        })

      def cede: PureConc[E, Unit] =
        withCtx { ctx =>
          M.productR(Thread.cede)(M.flatMap(ctx.self.realizeCancelationWith(ctx)) { canceled =>
            if (canceled) Thread.done else M.unit
          })
        }

      def never[A]: PureConc[E, A] =
        withCtx[E, A] { ctx =>
          // we monitor for asynchronous cancelation. if we're masked, this won't cancel and we hang
          Thread.annotate("never")(M.productR(ctx.self.awaitCancelationWith(ctx))(Thread.done))
        }

      def ref[A](a: A): PureConc[E, Ref[PureConc[E, *], A]] =
        M.map(mvar(a))(unsafeRef(_))

      def deferred[A]: PureConc[E, Deferred[PureConc[E, *], A]] =
        M.map(emptyMVar[A])(unsafeDeferred(_))

      private[this] def interruptible[A](ctx: FiberCtx[E], fa: PureConc[E, A]): PureConc[E, A] =
        ctx.self.interruptible(ctx)(fa)

      private def unsafeRef[A](mVar: MVar[A]): Ref[PureConc[E, *], A] =
        new Ref[PureConc[E, *], A] {
          override def get: PureConc[E, A] = readMVar(mVar)

          override def set(a: A): PureConc[E, Unit] = modify(_ => (a, ()))

          override def access: PureConc[E, (A, A => PureConc[E, Boolean])] =
            uncancelable { _ =>
              M.flatMap(readMVar(mVar)) { a =>
                M.map(emptyMVar[Unit]) { called =>
                  val setter = (au: A) =>
                    M.flatMap(tryPutMVar(called, ())) { alreadyCalled =>
                      if (alreadyCalled) M.pure(false)
                      else
                        M.flatMap(takeMVar(mVar)) { ay =>
                          if (a == ay) M.as(putMVar(mVar, au), true)
                          else M.pure(false)
                        }
                    }
                  (a, setter)
                }
              }
            }

          override def tryUpdate(f: A => A): PureConc[E, Boolean] =
            M.as(update(f), true)

          override def tryModify[B](f: A => (A, B)): PureConc[E, Option[B]] =
            M.map(modify(f))(Some(_))

          override def update(f: A => A): PureConc[E, Unit] =
            uncancelable { _ => M.flatMap(takeMVar(mVar))(a => putMVar(mVar, f(a))) }

          override def modify[B](f: A => (A, B)): PureConc[E, B] =
            uncancelable { _ =>
              M.flatMap(takeMVar(mVar)) { a =>
                val (a2, b) = f(a)
                M.as(putMVar(mVar, a2), b)
              }
            }

          override def tryModifyState[B](state: State[A, B]): PureConc[E, Option[B]] = {
            val f = state.runF.value
            tryModify(a => f(a).value)
          }

          override def modifyState[B](state: State[A, B]): PureConc[E, B] = {
            val f = state.runF.value
            modify(a => f(a).value)
          }
        }

      private def unsafeDeferred[A](mVar: MVar[A]): Deferred[PureConc[E, *], A] =
        new Deferred[PureConc[E, *], A] {
          override def get: PureConc[E, A] =
            withCtx { ctx => interruptible(ctx, readMVar(mVar)) }

          override def complete(a: A): PureConc[E, Boolean] = tryPutMVar(mVar, a)

          override def tryGet: PureConc[E, Option[A]] = tryReadMVar(mVar)
        }

      def start[A](fa: PureConc[E, A]): PureConc[E, Fiber[PureConc[E, *], E, A]] =
        Thread.annotate("start", true) {
          M.flatMap(emptyMVar[Outcome[PureConc[E, *], E, A]]) { state =>
            M.flatMap(emptyMVar[Unit]) { canceled =>
              M.flatMap(mvar(FiberState[E](Nil, 0, Nil))) { fiberState =>
                M.flatMap(mvar(List.empty[CancelationListener[E]])) { cancelationListeners =>
                  M.flatMap(mvar(false)) { finalizing =>
                    val fiber =
                      new PureFiber[E, A](
                        state,
                        canceled,
                        fiberState,
                        cancelationListeners,
                        finalizing)

                    // This is the RunTerminusK analogue: completion is not a user continuation.
                    val body =
                      M.handleErrorWith(
                        M.flatMap(fa)(a => fiber.complete(Outcome.Succeeded(M.pure(a)))))(e =>
                        fiber.complete(Outcome.Errored(e)))

                    val identified = localCtx(FiberCtx(fiber), body)
                    M.as(Thread.start(M.void(M.attempt(identified))), fiber)
                  }
                }
              }
            }
          }
        }

      override def racePair[A, B](fa: PureConc[E, A], fb: PureConc[E, B]): PureConc[
        E,
        Either[
          (Outcome[PureConc[E, *], E, A], Fiber[PureConc[E, *], E, B]),
          (Fiber[PureConc[E, *], E, A], Outcome[PureConc[E, *], E, B])]] =
        uncancelable { poll =>
          for {
            result <- deferred[
              Either[Outcome[PureConc[E, *], E, A], Outcome[PureConc[E, *], E, B]]]

            fibA <- start(fa)
            fibB <- start(fb)

            _ <- start(
              fibA
                .join
                .flatMap(oc =>
                  result
                    .complete(Left(oc): Either[
                      Outcome[PureConc[E, *], E, A],
                      Outcome[PureConc[E, *], E, B]])
                    .void))
            _ <- start(
              fibB
                .join
                .flatMap(oc =>
                  result
                    .complete(Right(oc): Either[
                      Outcome[PureConc[E, *], E, A],
                      Outcome[PureConc[E, *], E, B]])
                    .void))

            back <- onCancel(
              poll(result.get),
              for {
                canA <- start(fibA.cancel)
                canB <- start(fibB.cancel)

                _ <- canA.join
                _ <- canB.join
              } yield ())
          } yield back match {
            case Left(oc) => Left((oc, fibB))
            case Right(oc) => Right((fibA, oc))
          }
        }

      def uncancelable[A](body: Poll[PureConc[E, *]] => PureConc[E, A]): PureConc[E, A] =
        Thread.annotate("uncancelable", true) {
          withCtx { ctx =>
            val mask = new MaskId
            val self = ctx.self

            def updateState[B](stateCtx: FiberCtx[E])(
                f: FiberState[E] => (FiberState[E], B)): PureConc[E, B] =
              M.flatMap(readMVar(stateCtx.self.fiberState)) { state =>
                val (updated, b) = f(state)
                M.as(swapMVar(stateCtx.self.fiberState, updated), b)
              }

            val addF =
              updateState(ctx)(state =>
                (state.copy(frames = MaskFrame(mask) :: state.frames), ()))

            val removeF =
              updateState(ctx) { state =>
                state.frames match {
                  case MaskFrame(`mask`) :: frames =>
                    (state.copy(frames = frames), MaskUpdate.Removed)

                  case frames if frames.exists(_.id === mask) =>
                    (state, MaskUpdate.Shadowed)

                  case _ =>
                    (state, MaskUpdate.Absent)
                }
              }

            def enterPoll(callCtx: FiberCtx[E]) =
              updateState(callCtx) { state =>
                state.frames match {
                  case MaskFrame(`mask`) :: frames =>
                    (
                      state.copy(frames = frames, activePolls = state.activePolls + 1),
                      MaskUpdate.Removed)

                  case frames if frames.exists(_.id === mask) =>
                    (state, MaskUpdate.Shadowed)

                  case _ =>
                    (state, MaskUpdate.Absent)
                }
              }

            def restore(callCtx: FiberCtx[E], update: MaskUpdate) =
              update match {
                case MaskUpdate.Removed =>
                  updateState(callCtx) { state =>
                    val activePolls = math.max(0, state.activePolls - 1)

                    (
                      state.copy(
                        frames = MaskFrame(mask) :: state.frames,
                        activePolls = activePolls),
                      ())
                  }
                case MaskUpdate.Shadowed | MaskUpdate.Absent => M.unit
              }

            val poll = new Poll[PureConc[E, *]] {
              def apply[a](fa: PureConc[E, a]) =
                withCtx { callCtx =>
                  if (callCtx.self eq self)
                    M.flatMap(enterPoll(callCtx)) { update =>
                      val restoreF = restore(callCtx, update)

                      update match {
                        case MaskUpdate.Removed =>
                          onCancelWith(
                            cancelationBoundaryWith(
                              callCtx,
                              M.flatMap(M.attempt(fa)) { result =>
                                M.flatMap(restoreF)(_ => M.rethrow(M.pure(result)))
                              }),
                            restoreF)

                        case MaskUpdate.Shadowed | MaskUpdate.Absent =>
                          fa
                      }
                    }
                  else
                    fa
                }
            }

            // UncancelableK and UnmaskK restore their frames before user continuations run.
            val runBody =
              M.flatMap(addF) { _ =>
                M.flatMap(M.attempt(body(poll))) { result =>
                  M.flatMap(removeF)(_ => M.rethrow(M.pure(result)))
                }
              }

            onCancelWith(runBody, M.void(removeF))
          }
        }

      // we happen to know this is non-memoizing, so we're just using it as a shortcut
      def unique: PureConc[E, Unique.Token] =
        Defer[PureConc[E, *]].defer(pure(new Unique.Token()))

      def forceR[A, B](fa: PureConc[E, A])(fb: PureConc[E, B]): PureConc[E, B] =
        Thread.annotate("forceR")(productR(handleError(fa.void)(_ => ()))(fb))

      def flatMap[A, B](fa: PureConc[E, A])(f: A => PureConc[E, B]): PureConc[E, B] =
        M.flatMap(fa)(a => cancelationBoundary(f(a)))

      def tailRecM[A, B](a: A)(f: A => PureConc[E, Either[A, B]]): PureConc[E, B] =
        M.tailRecM(a)(a => cancelationBoundary(f(a)))
    }

  implicit def eqPureConc[E: Eq, A: Eq]: Eq[PureConc[E, A]] = Eq.by(run(_))

  implicit def showPureConc[E: Show, A: Show]: Show[PureConc[E, A]] =
    Show show { pc =>
      val trace = ThreadT
        .prettyPrint(resolveMain(pc), limit = 4096)
        .fold("Canceled", e => s"Errored(${e.show})", str => str.replaceAll("╭ ", "├ "))

      run(pc).show + "\n│\n" + trace
    }

  private[this] def mvarLiftF[F[_], A](fa: F[A]): MVarR[F, A] =
    Kleisli.liftF[F, MVar.Universe, A](fa)

  private[this] def rawMonad[E]: MonadError[PureConc[E, *], E] =
    Kleisli.catsDataMonadErrorForKleisli

  // this would actually be a very usful function for FreeT to have
  private[this] def flattenK[S[_]: Functor, M[_]: Monad, A](
      ft: FreeT[S, FreeT[S, M, *], A]): FreeT[S, M, A] =
    ft.resume
      .flatMap(
        _.fold(
          sft => FreeT.liftF[S, M, FreeT[S, FreeT[S, M, *], A]](sft).flatMap(flattenK(_)),
          FreeT.pure(_)))

  // the type inferencer just... fails... completely here
  private[this] def withCtx[E, A](body: FiberCtx[E] => PureConc[E, A]): PureConc[E, A] =
    rawMonad[E].flatten(
      mvarLiftF(ThreadT.liftF(Kleisli.ask[IdOC[E, *], FiberCtx[E]].map(body))))
  // ApplicativeAsk[PureConc[E, *], FiberCtx[E]].ask.flatMap(body)

  private[this] def localCtx[E, A](ctx: FiberCtx[E], around: PureConc[E, A]): PureConc[E, A] =
    around.mapF { ft =>
      val fk = new (FiberR[E, *] ~> FiberR[E, *]) {
        def apply[a](ka: FiberR[E, a]) =
          Kleisli(_ => ka.run(ctx))
      }

      ft.mapK(fk)
    }

  // todo: MVar is not Serializable, release then update here
  final class PureFiber[E, A](
      val state0: MVar[Outcome[PureConc[E, *], E, A]],
      private[this] val canceled0: MVar[Unit],
      private[pure] val fiberState: MVar[FiberState[E]],
      private[this] val cancelationListeners: MVar[List[CancelationListener[E]]],
      private[this] val finalizing: MVar[Boolean])
      extends Fiber[PureConc[E, *], E, A]
      with Serializable {

    private[this] val M: MonadError[PureConc[E, *], E] = rawMonad[E]
    private[this] val Thread = ApplicativeThread[PureConc[E, *]]
    private[this] val Ask = implicitly[MVar.Ask[PureConc[E, *]]]

    private[this] def emptyMVar[B]: PureConc[E, MVar[B]] =
      MVar.empty[PureConc[E, *], B](M, Thread)

    private[this] def readMVar[B](mvar: MVar[B]): PureConc[E, B] =
      mvar.read[PureConc[E, *]](M, Thread, Ask)

    private[this] def tryReadMVar[B](mvar: MVar[B]): PureConc[E, Option[B]] =
      mvar.tryRead[PureConc[E, *]](M, Ask)

    private[this] def tryPutMVar[B](mvar: MVar[B], value: B): PureConc[E, Boolean] =
      mvar.tryPut[PureConc[E, *]](value)(M, Thread, Ask)

    private[this] def swapMVar[B](mvar: MVar[B], value: B): PureConc[E, B] =
      mvar.swap[PureConc[E, *]](value)(M, Thread, Ask)

    def this(state0: MVar[Outcome[PureConc[E, *], E, A]]) =
      this(state0, null, null, null, null)

    // Retained for binary compatibility with the former split mask/poll state constructor.
    def this(
        state0: MVar[Outcome[PureConc[E, *], E, A]],
        canceled0: MVar[Unit],
        _masks: MVar[List[MaskFrame]],
        cancelationListeners: MVar[List[CancelationListener[E]]],
        finalizing: MVar[Boolean],
        _activePolls: MVar[List[List[Finalizer[E]]]]) = {
      this(state0, canceled0, null, cancelationListeners, finalizing)
      val _ = (_masks, _activePolls)
    }

    private[this] val state = state0[PureConc[E, *]](M, Thread, Ask)

    private[pure] val currentMasks: PureConc[E, List[MaskFrame]] =
      if (fiberState eq null) M.pure(List.empty[MaskFrame])
      else M.map(readMVar(fiberState))(_.frames)

    private[pure] val hasActivePoll: PureConc[E, Boolean] =
      if (fiberState eq null) M.pure(false)
      else M.map(readMVar(fiberState))(_.activePolls > 0)

    private[pure] def registerFinalizer(action: Finalizer[E]): PureConc[E, FinalizerId] = {
      val id = new FinalizerId

      if (fiberState eq null) M.pure(id)
      else
        M.flatMap(readMVar(fiberState)) { state =>
          M.as(
            swapMVar(
              fiberState,
              state.copy(finalizers = RegisteredFinalizer(id, action) :: state.finalizers)),
            id)
        }
    }

    private[pure] def removeFinalizer(id: FinalizerId): PureConc[E, Unit] =
      if (fiberState eq null) M.unit
      else
        M.flatMap(readMVar(fiberState)) { state =>
          M.void(
            swapMVar(
              fiberState,
              state.copy(finalizers = state.finalizers.filterNot(_.id eq id))))
        }

    private[pure] def complete(outcome: Outcome[PureConc[E, *], E, A]): PureConc[E, Unit] =
      M.productR(setFinalizing(true))(M.void(tryPutMVar(state0, outcome)))

    private[pure] def registerCancelationListener(
        notify: PureConc[E, Unit]): PureConc[E, CancelationListenerId] = {
      val id = new CancelationListenerId

      if (cancelationListeners eq null) M.pure(id)
      else
        M.flatMap(readMVar(cancelationListeners)) { listeners =>
          M.as(swapMVar(cancelationListeners, CancelationListener(id, notify) :: listeners), id)
        }
    }

    private[pure] def removeCancelationListener(id: CancelationListenerId): PureConc[E, Unit] =
      if (cancelationListeners eq null) M.unit
      else
        M.flatMap(readMVar(cancelationListeners)) { listeners =>
          M.void(swapMVar(cancelationListeners, listeners.filterNot(_.id === id)))
        }

    private[this] def notifyCancelationListeners: PureConc[E, Unit] =
      if (cancelationListeners eq null) M.unit
      else
        M.flatMap(swapMVar(cancelationListeners, Nil))(
          _.foldLeft(M.unit)((acc, listener) => M.productR(acc)(listener.action)))

    private[pure] def interruptible[B](ctx: FiberCtx[E])(fb: PureConc[E, B]): PureConc[E, B] = {
      M.flatMap(ctx.self.currentMasks) {
        case Nil =>
          M.flatMap(emptyMVar[Option[B]]) { signal =>
            val notifyCancelation = M.void(tryPutMVar(signal, None))

            M.flatMap(ctx.self.registerCancelationListener(notifyCancelation)) { listener =>
              val awaitCompletion =
                Thread.start(M.flatMap(fb)(b => M.void(tryPutMVar(signal, Some(b)))))

              val checkCancelation =
                M.flatMap(tryReadMVar(signal)) {
                  case Some(_) => M.unit
                  case None =>
                    M.flatMap(ctx.self.realizeCancelationWith(ctx)) { canceled =>
                      if (canceled) notifyCancelation else M.unit
                    }
                }

              M.productR(awaitCompletion)(
                M.productR(checkCancelation)(M.flatMap(readMVar(signal)) {
                  case Some(b) =>
                    M.as(ctx.self.removeCancelationListener(listener), b)

                  case None =>
                    M.productR(ctx.self.removeCancelationListener(listener))(
                      M.productR(ctx.self.realizeCancelationWith(ctx))(Thread.done))
                }))
            }
          }

        case _ =>
          fb
      }
    }

    private[pure] val isFinalizing: PureConc[E, Boolean] =
      if (finalizing eq null) M.pure(false)
      else readMVar(finalizing)

    private[this] def setFinalizing(value: Boolean): PureConc[E, Unit] =
      if (finalizing eq null) M.unit
      else M.void(swapMVar(finalizing, value))

    private[this] def finalizeWith(
        ctx: FiberCtx[E],
        finalizers: List[PureConc[E, Unit]]): PureConc[E, Boolean] =
      localCtx(
        ctx.withFinalizers(Nil).withFinalizing(true),
        M.productR(allocateForPureConc[E].uncancelable(_ =>
          finalizers.foldLeft(M.unit)((acc, finalizer) => M.productR(acc)(finalizer))))(
          M.productL(
            M.flatMap(tryPutMVar(state0, Outcome.Canceled(): Outcome[PureConc[E, *], E, A])) {
              case true => M.pure(true)
              case false =>
                M.map(state.read) {
                  case Outcome.Canceled() => true
                  case _ => false
                }
            })(setFinalizing(false)))
      )

    private[this] def whileFinalizing[B](ctx: FiberCtx[E])(fb: PureConc[E, B]): PureConc[E, B] =
      localCtx(
        ctx.withFinalizers(Nil).withFinalizing(true),
        M.productR(setFinalizing(true))(fb))

    private[this] def finalizationOutcome: PureConc[E, Boolean] =
      M.map(state.read) {
        case Outcome.Canceled() => true
        case _ => false
      }

    private[this] def realizeCancelationWith(
        ctx: FiberCtx[E],
        deferWhileFinalizersRegistered: Boolean): PureConc[E, Boolean] =
      if (ctx.finalizing) M.pure(false)
      else
        M.flatMap(isFinalizing) { finalizing =>
          if (finalizing) finalizationOutcome
          else
            M.flatMap(tryReadMVar(canceled0)) {
              case Some(_) =>
                M.flatMap(readMVar(fiberState)) { state =>
                  if (state.frames.isEmpty &&
                    (!deferWhileFinalizersRegistered || state.finalizers.isEmpty))
                    whileFinalizing(ctx)(finalizeWith(ctx, state.finalizers.map(_.action)))
                  else
                    M.pure(false)
                }
              case None => M.pure(false)
            }
        }

    private[pure] def realizeCancelationWith(ctx: FiberCtx[E]): PureConc[E, Boolean] =
      realizeCancelationWith(ctx, deferWhileFinalizersRegistered = false)

    // The evaluator runs outside localCtx, so it must let successful finalizer frames unwind.
    // Explicit user boundaries call realizeCancelationWith and do not defer.
    private[pure] def realizeCancelationAtEvaluatorBoundaryWith(
        ctx: FiberCtx[E]): PureConc[E, Boolean] =
      realizeCancelationWith(ctx, deferWhileFinalizersRegistered = true)

    private[pure] def awaitCancelationWith(ctx: FiberCtx[E]): PureConc[E, Boolean] = {
      def blocked =
        M.as(M.flatMap(emptyMVar[Unit])(readMVar), false)

      if (ctx.finalizing) blocked
      else
        M.flatMap(ctx.self.currentMasks) {
          case Nil =>
            M.flatMap(isFinalizing) { finalizing =>
              if (finalizing)
                M.map(tryReadMVar(canceled0))(_.isEmpty)
              else
                M.productR(readMVar(canceled0))(realizeCancelationWith(ctx))
            }

          case _ =>
            M.flatMap(isFinalizing) { finalizing =>
              if (finalizing)
                M.map(tryReadMVar(canceled0))(_.isEmpty)
              else blocked
            }
        }
    }

    private[pure] def cancelAndRealizeWith(ctx: FiberCtx[E]): PureConc[E, Boolean] =
      if (ctx.finalizing) M.pure(false)
      else
        M.flatMap(isFinalizing) { finalizing =>
          if (finalizing) M.map(ctx.self.currentMasks)(_.isEmpty)
          else
            M.flatMap(readMVar(fiberState)) { state =>
              if (state.frames.isEmpty)
                whileFinalizing(ctx) {
                  M.productR(requestCancelation)(
                    finalizeWith(ctx, state.finalizers.map(_.action)))
                }
              else
                M.as(requestCancelation, false)
            }
        }

    private[this] def requestCancelation: PureConc[E, Unit] =
      M.flatMap(tryPutMVar(canceled0, ()))(inserted =>
        if (inserted) notifyCancelationListeners else M.unit)

    val join: PureConc[E, Outcome[PureConc[E, *], E, A]] =
      if (canceled0 eq null) state.read
      else {
        withCtx { ctx => ctx.self.interruptible(ctx)(state.read) }
      }

    val cancel: PureConc[E, Unit] =
      if (canceled0 eq null)
        M.void(tryPutMVar(state0, Outcome.Canceled(): Outcome[PureConc[E, *], E, A]))
      else
        allocateForPureConc[E].uncancelable { _ =>
          M.flatMap(tryReadMVar(state0)) {
            case Some(_) => M.unit
            case None =>
              M.productR(requestCancelation)(M.void(state.read))
          }
        }
  }
}
