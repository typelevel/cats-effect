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

  private[pure] sealed trait CancelationSignal

  private[pure] object CancelationSignal {
    case object Self extends CancelationSignal
    case object External extends CancelationSignal
  }

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
    /*
     * The cancelation implementation is here. The failures of type inference make this look
     * HORRIBLE but the general idea is fairly simple: mapK over the FreeT into a new monad
     * which sequences a cancelation check within each flatten. Thus, we go from Kleisli[FreeT[Kleisli[Outcome[Id, ...]]]]
     * to Kleisli[FreeT[Kleisli[FreeT[Kleisli[Outcome[Id, ...]]]]]]]], which we then need to go
     * through and flatten. The cancelation check *itself* is in `cancelationCheck`, while the flattening
     * process is in the definition of `val canceled`.
     *
     * FlatMapK and TraverseK typeclasses would make this a one-liner.
     */

    val cancelationCheck = new (FiberR[E, *] ~> PureConc[E, *]) {
      def apply[α](ka: FiberR[E, α]): PureConc[E, α] = {
        val back = Kleisli.ask[IdOC[E, *], FiberCtx[E]] map { ctx =>
          val checker = ctx
            .self
            .hasActivePoll
            .ifM(
              ().pure[PureConc[E, *]],
              ctx
                .self
                .isFinalizing
                .ifM(
                  ().pure[PureConc[E, *]],
                  ctx
                    .self
                    .realizeCancelationWith(ctx)
                    .ifM(ApplicativeThread[PureConc[E, *]].done[Unit], ().pure[PureConc[E, *]]))
            )

          checker >> mvarLiftF(ThreadT.liftF(ka))
        }

        mvarLiftF(ThreadT.liftF(back)).flatten
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
        MVar.empty[Main, CancelationSignal] flatMap { canceled0 =>
          MVar[Main, List[MaskFrame]](Nil) flatMap { masks =>
            MVar[Main, List[CancelationListener[E]]](Nil) flatMap { cancelationListeners =>
              MVar[Main, Boolean](false) flatMap { finalizing =>
                MVar[Main, List[List[Finalizer[E]]]](Nil) flatMap { activePolls =>
                  val state = state0[Main]
                  val fiber =
                    new PureFiber[E, A](
                      state0,
                      canceled0,
                      masks,
                      cancelationListeners,
                      finalizing,
                      activePolls)

                  val identified = canceled mapF { ta =>
                    val fk = new (FiberR[E, *] ~> IdOC[E, *]) {
                      def apply[a](ke: FiberR[E, a]) =
                        ke.run(FiberCtx(fiber))
                    }

                    ta.mapK(fk)
                  }

                  import Outcome._

                  val body = identified flatMap { a =>
                    state.tryPut(Succeeded(a.pure[PureConc[E, *]]))
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
      private[this] val M: MonadError[PureConc[E, *], E] =
        Kleisli.catsDataMonadErrorForKleisli

      private[this] val Thread = ApplicativeThread[PureConc[E, *]]

      def pure[A](x: A): PureConc[E, A] =
        M.pure(x)

      def handleErrorWith[A](fa: PureConc[E, A])(f: E => PureConc[E, A]): PureConc[E, A] =
        Thread.annotate("handleErrorWith", true)(M.handleErrorWith(fa)(f))

      def raiseError[A](e: E): PureConc[E, A] =
        Thread.annotate("raiseError")(M.raiseError(e))

      def onCancel[A](fa: PureConc[E, A], fin: PureConc[E, Unit]): PureConc[E, A] =
        Thread.annotate("onCancel", true) {
          withCtx[E, A] { ctx =>
            val ctx2 = ctx.withFinalizers(fin.attempt.void :: ctx.finalizers)
            localCtx(ctx2, fa)
          }
        }

      def canceled: PureConc[E, Unit] =
        Thread.annotate("canceled")(withCtx { ctx =>
          ctx.self.cancelAndRealizeWith(ctx).ifM(Thread.done, unit)
        })

      def cede: PureConc[E, Unit] =
        withCtx { ctx =>
          Thread.cede *>
            ctx.self.realizeCancelationWith(ctx).ifM(Thread.done, unit)
        }

      def never[A]: PureConc[E, A] =
        withCtx[E, A] { ctx =>
          // we monitor for asynchronous cancelation. if we're masked, this won't cancel and we hang
          Thread.annotate("never")(ctx.self.awaitCancelationWith(ctx) *> Thread.done)
        }

      def ref[A](a: A): PureConc[E, Ref[PureConc[E, *], A]] =
        MVar[PureConc[E, *], A](a).flatMap(mVar => Kleisli.pure(unsafeRef(mVar)))

      def deferred[A]: PureConc[E, Deferred[PureConc[E, *], A]] =
        MVar.empty[PureConc[E, *], A].flatMap(mVar => Kleisli.pure(unsafeDeferred(mVar)))

      private[this] def interruptible[A](ctx: FiberCtx[E], fa: PureConc[E, A]): PureConc[E, A] =
        ctx.self.interruptible(ctx)(fa)

      private def unsafeRef[A](mVar: MVar[A]): Ref[PureConc[E, *], A] =
        new Ref[PureConc[E, *], A] {
          override def get: PureConc[E, A] = mVar.read[PureConc[E, *]]

          override def set(a: A): PureConc[E, Unit] = modify(_ => (a, ()))

          override def access: PureConc[E, (A, A => PureConc[E, Boolean])] =
            uncancelable { _ =>
              mVar.read[PureConc[E, *]].flatMap { a =>
                MVar.empty[PureConc[E, *], Unit].map { called =>
                  val setter = (au: A) =>
                    called
                      .tryPut[PureConc[E, *]](())
                      .ifM(
                        pure(false),
                        mVar.take[PureConc[E, *]].flatMap { ay =>
                          if (a == ay) mVar.put[PureConc[E, *]](au).as(true) else pure(false)
                        })
                  (a, setter)
                }
              }
            }

          override def tryUpdate(f: A => A): PureConc[E, Boolean] =
            update(f).as(true)

          override def tryModify[B](f: A => (A, B)): PureConc[E, Option[B]] =
            modify(f).map(Some(_))

          override def update(f: A => A): PureConc[E, Unit] =
            uncancelable { _ =>
              mVar.take[PureConc[E, *]].flatMap(a => mVar.put[PureConc[E, *]](f(a)))
            }

          override def modify[B](f: A => (A, B)): PureConc[E, B] =
            uncancelable { _ =>
              mVar.take[PureConc[E, *]].flatMap { a =>
                val (a2, b) = f(a)
                mVar.put[PureConc[E, *]](a2).as(b)
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
            withCtx { ctx => interruptible(ctx, mVar.read[PureConc[E, *]]) }

          override def complete(a: A): PureConc[E, Boolean] = mVar.tryPut[PureConc[E, *]](a)

          override def tryGet: PureConc[E, Option[A]] = mVar.tryRead[PureConc[E, *]]
        }

      def start[A](fa: PureConc[E, A]): PureConc[E, Fiber[PureConc[E, *], E, A]] =
        Thread.annotate("start", true) {
          MVar.empty[PureConc[E, *], Outcome[PureConc[E, *], E, A]].flatMap { state =>
            MVar.empty[PureConc[E, *], CancelationSignal] flatMap { canceled =>
              MVar[PureConc[E, *], List[MaskFrame]](Nil) flatMap { masks =>
                MVar[PureConc[E, *], List[CancelationListener[E]]](Nil) flatMap {
                  cancelationListeners =>
                    MVar[PureConc[E, *], Boolean](false) flatMap { finalizing =>
                      MVar[PureConc[E, *], List[List[Finalizer[E]]]](Nil) flatMap {
                        activePolls =>
                          val fiber =
                            new PureFiber[E, A](
                              state,
                              canceled,
                              masks,
                              cancelationListeners,
                              finalizing,
                              activePolls)

                          // the tryPut here is interesting: it encodes first-wins semantics on cancelation/completion
                          val body = guaranteeCase(fa)(state.tryPut[PureConc[E, *]](_).void)
                          val identified = localCtx(FiberCtx(fiber), body)
                          Thread.start(identified.attempt.void).as(fiber)
                      }
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
            def updateMasks[B](f: List[MaskFrame] => (List[MaskFrame], B)): PureConc[E, B] =
              self.masks.read[PureConc[E, *]].flatMap { ms =>
                val (updated, b) = f(ms)
                self.masks.swap[PureConc[E, *]](updated).as(b)
              }

            val addF = updateMasks(ms => (MaskFrame(mask) :: ms, ()))
            val removeF = updateMasks {
              case MaskFrame(`mask`) :: ms => (ms, MaskUpdate.Removed)
              case ms if ms.exists(_.id === mask) => (ms, MaskUpdate.Shadowed)
              case ms => (ms, MaskUpdate.Absent)
            }

            def restore(update: MaskUpdate) =
              update match {
                case MaskUpdate.Removed => self.exitPoll *> addF
                case MaskUpdate.Shadowed | MaskUpdate.Absent => unit
              }

            val poll = new Poll[PureConc[E, *]] {
              def apply[a](fa: PureConc[E, a]) =
                withCtx { callCtx =>
                  if (callCtx.self eq self)
                    removeF.flatMap { update =>
                      val restoreF = restore(update)

                      val enterF = update match {
                        case MaskUpdate.Removed => self.enterPoll(callCtx.finalizers)
                        case MaskUpdate.Shadowed | MaskUpdate.Absent => unit
                      }

                      enterF *>
                        localCtx(
                          callCtx,
                          onCancel(
                            self
                              .realizeCancelationWith(callCtx)
                              .ifM(
                                Thread.done,
                                fa.attempt.flatMap { result =>
                                  self
                                    .realizeCancelationWith(callCtx)
                                    .ifM(
                                      Thread.done,
                                      restoreF *> result.pure[PureConc[E, *]].rethrow)
                                }),
                            restoreF
                          )
                        )
                    }
                  else fa
                }
            }

            val runBody =
              addF *> body(poll).attempt.flatMap { result =>
                removeF.flatMap {
                  case MaskUpdate.Removed =>
                    val back = result.pure[PureConc[E, *]].rethrow

                    self.realizeCancelationWith(ctx).ifM(Thread.done, back)

                  case MaskUpdate.Shadowed | MaskUpdate.Absent =>
                    result.pure[PureConc[E, *]].rethrow
                }
              }

            onCancel(runBody, removeF.void)
          }
        }

      // we happen to know this is non-memoizing, so we're just using it as a shortcut
      def unique: PureConc[E, Unique.Token] =
        Defer[PureConc[E, *]].defer(pure(new Unique.Token()))

      def forceR[A, B](fa: PureConc[E, A])(fb: PureConc[E, B]): PureConc[E, B] =
        Thread.annotate("forceR")(productR(handleError(fa.void)(_ => ()))(fb))

      def flatMap[A, B](fa: PureConc[E, A])(f: A => PureConc[E, B]): PureConc[E, B] =
        M.flatMap(fa)(f)

      def tailRecM[A, B](a: A)(f: A => PureConc[E, Either[A, B]]): PureConc[E, B] =
        M.tailRecM(a)(f)
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
    mvarLiftF(ThreadT.liftF(Kleisli.ask[IdOC[E, *], FiberCtx[E]].map(body))).flatten
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
      private[this] val canceled0: MVar[CancelationSignal],
      private[pure] val masks: MVar[List[MaskFrame]],
      private[this] val cancelationListeners: MVar[List[CancelationListener[E]]],
      private[this] val finalizing: MVar[Boolean],
      private[this] val activePolls: MVar[List[List[Finalizer[E]]]])
      extends Fiber[PureConc[E, *], E, A]
      with Serializable {

    def this(state0: MVar[Outcome[PureConc[E, *], E, A]]) =
      this(state0, null, null, null, null, null)

    private[this] val state = state0[PureConc[E, *]]

    private[pure] val currentMasks: PureConc[E, List[MaskFrame]] =
      if (masks eq null) List.empty[MaskFrame].pure[PureConc[E, *]]
      else masks.read[PureConc[E, *]]

    private[pure] val hasActivePoll: PureConc[E, Boolean] =
      if (activePolls eq null) false.pure[PureConc[E, *]]
      else activePolls.read[PureConc[E, *]].map(_.nonEmpty)

    private[pure] def enterPoll(finalizers: List[Finalizer[E]]): PureConc[E, Unit] =
      if (activePolls eq null) ().pure[PureConc[E, *]]
      else
        activePolls.read[PureConc[E, *]].flatMap { polls =>
          activePolls.swap[PureConc[E, *]](finalizers :: polls).void
        }

    private[pure] val exitPoll: PureConc[E, Unit] =
      if (activePolls eq null) ().pure[PureConc[E, *]]
      else
        activePolls.read[PureConc[E, *]].flatMap {
          case _ :: polls => activePolls.swap[PureConc[E, *]](polls).void
          case Nil => ().pure[PureConc[E, *]]
        }

    private[pure] val currentPollFinalizers: PureConc[E, List[Finalizer[E]]] =
      if (activePolls eq null) List.empty[Finalizer[E]].pure[PureConc[E, *]]
      else activePolls.read[PureConc[E, *]].map(_.headOption.getOrElse(Nil))

    private[pure] def registerCancelationListener(
        notify: PureConc[E, Unit]): PureConc[E, CancelationListenerId] = {
      val id = new CancelationListenerId

      if (cancelationListeners eq null) id.pure[PureConc[E, *]]
      else
        cancelationListeners.read[PureConc[E, *]].flatMap { listeners =>
          cancelationListeners
            .swap[PureConc[E, *]](CancelationListener(id, notify) :: listeners)
            .as(id)
        }
    }

    private[pure] def removeCancelationListener(id: CancelationListenerId): PureConc[E, Unit] =
      if (cancelationListeners eq null) ().pure[PureConc[E, *]]
      else
        cancelationListeners.read[PureConc[E, *]].flatMap { listeners =>
          cancelationListeners.swap[PureConc[E, *]](listeners.filterNot(_.id === id)).void
        }

    private[this] def notifyCancelationListeners: PureConc[E, Unit] =
      if (cancelationListeners eq null) ().pure[PureConc[E, *]]
      else cancelationListeners.swap[PureConc[E, *]](Nil).flatMap(_.traverse_(_.action))

    private[pure] def interruptible[B](ctx: FiberCtx[E])(fb: PureConc[E, B]): PureConc[E, B] = {
      val Thread = ApplicativeThread[PureConc[E, *]]

      ctx.self.currentMasks.flatMap {
        case Nil =>
          MVar.empty[PureConc[E, *], Option[B]].flatMap { signal =>
            val notifyCancelation = signal.tryPut[PureConc[E, *]](None).void

            ctx.self.registerCancelationListener(notifyCancelation).flatMap { listener =>
              val awaitCompletion =
                Thread.start(fb.flatMap(b => signal.tryPut[PureConc[E, *]](Some(b)).void))

              val checkCancelation =
                signal.tryRead[PureConc[E, *]].flatMap {
                  case Some(_) => ().pure[PureConc[E, *]]
                  case None =>
                    ctx
                      .self
                      .realizeCancelationWith(ctx)
                      .ifM(notifyCancelation, ().pure[PureConc[E, *]])
                }

              awaitCompletion *>
                checkCancelation *>
                signal.read[PureConc[E, *]].flatMap {
                  case Some(b) =>
                    ctx.self.removeCancelationListener(listener).as(b)

                  case None =>
                    ctx.self.removeCancelationListener(listener) *>
                      ctx.self.realizeCancelationWith(ctx) *>
                      Thread.done
                }
            }
          }

        case _ =>
          fb
      }
    }

    private[pure] val isFinalizing: PureConc[E, Boolean] =
      if (finalizing eq null) false.pure[PureConc[E, *]]
      else finalizing.read[PureConc[E, *]]

    private[this] def setFinalizing(value: Boolean): PureConc[E, Unit] =
      if (finalizing eq null) ().pure[PureConc[E, *]]
      else finalizing.swap[PureConc[E, *]](value).void

    private[this] def finalizeWith(
        ctx: FiberCtx[E],
        finalizers: List[PureConc[E, Unit]]): PureConc[E, Boolean] =
      localCtx(
        ctx.withFinalizers(Nil).withFinalizing(true),
        allocateForPureConc[E].uncancelable(_ => finalizers.sequence_) *>
          (state0.tryPut[PureConc[E, *]](Outcome.Canceled()).flatMap {
            case true => true.pure[PureConc[E, *]]
            case false =>
              state.read.map {
                case Outcome.Canceled() => true
                case _ => false
              }
          } <* setFinalizing(false))
      )

    private[this] def whileFinalizing[B](ctx: FiberCtx[E])(fb: PureConc[E, B]): PureConc[E, B] =
      localCtx(ctx.withFinalizers(Nil).withFinalizing(true), setFinalizing(true) *> fb)

    private[this] def finalizationOutcome: PureConc[E, Boolean] =
      state.read.map {
        case Outcome.Canceled() => true
        case _ => false
      }

    private[this] def cancelationFinalizers(
        signal: CancelationSignal,
        ctx: FiberCtx[E]): PureConc[E, List[Finalizer[E]]] =
      signal match {
        case CancelationSignal.Self => ctx.self.currentPollFinalizers
        case CancelationSignal.External => ctx.finalizers.pure[PureConc[E, *]]
      }

    private[this] def realizeCancelationWithSignal(
        ctx: FiberCtx[E],
        signal: CancelationSignal): PureConc[E, Boolean] =
      if (ctx.finalizing) false.pure[PureConc[E, *]]
      else
        isFinalizing.ifM(
          finalizationOutcome,
          ctx
            .self
            .currentMasks
            .map(_.isEmpty)
            .ifM(
              cancelationFinalizers(signal, ctx)
                .flatMap(finalizers => whileFinalizing(ctx)(finalizeWith(ctx, finalizers))),
              false.pure[PureConc[E, *]]
            )
        )

    private[pure] def realizeCancelationWith(ctx: FiberCtx[E]): PureConc[E, Boolean] =
      if (ctx.finalizing) false.pure[PureConc[E, *]]
      else
        isFinalizing.ifM(
          finalizationOutcome,
          canceled0.tryRead[PureConc[E, *]].flatMap {
            case Some(signal) => realizeCancelationWithSignal(ctx, signal)
            case None => false.pure[PureConc[E, *]]
          }
        )

    private[pure] def awaitCancelationWith(ctx: FiberCtx[E]): PureConc[E, Boolean] = {
      def blocked =
        MVar.empty[PureConc[E, *], Unit].flatMap(_.read[PureConc[E, *]]).as(false)

      if (ctx.finalizing) blocked
      else
        ctx.self.currentMasks.flatMap {
          case Nil =>
            isFinalizing.ifM(
              canceled0.tryRead[PureConc[E, *]].map(_.isEmpty),
              canceled0.read[PureConc[E, *]].flatMap(realizeCancelationWithSignal(ctx, _)))

          case _ =>
            isFinalizing.ifM(canceled0.tryRead[PureConc[E, *]].map(_.isEmpty), blocked)
        }
    }

    private[pure] def cancelAndRealizeWith(ctx: FiberCtx[E]): PureConc[E, Boolean] =
      if (ctx.finalizing) false.pure[PureConc[E, *]]
      else
        isFinalizing.ifM(
          ctx.self.currentMasks.map(_.isEmpty),
          ctx.self.currentMasks.flatMap {
            case Nil =>
              whileFinalizing(ctx) {
                canceled0.tryPut[PureConc[E, *]](CancelationSignal.Self).flatMap {
                  case true =>
                    notifyCancelationListeners *> finalizeWith(ctx, ctx.finalizers)
                  case false => finalizeWith(ctx, ctx.finalizers)
                }
              }

            case _ =>
              requestCancelation(CancelationSignal.Self).as(false)
          }
        )

    private[this] def requestCancelation(signal: CancelationSignal): PureConc[E, Unit] =
      canceled0
        .tryPut[PureConc[E, *]](signal)
        .flatMap(inserted =>
          if (inserted) notifyCancelationListeners else ().pure[PureConc[E, *]])

    val join: PureConc[E, Outcome[PureConc[E, *], E, A]] =
      if (canceled0 eq null) state.read
      else {
        withCtx { ctx => ctx.self.interruptible(ctx)(state.read) }
      }

    val cancel: PureConc[E, Unit] =
      if (canceled0 eq null) state.tryPut(Outcome.Canceled()).void
      else
        allocateForPureConc[E].uncancelable { _ =>
          state.tryRead.flatMap {
            case Some(_) => ().pure[PureConc[E, *]]
            case None =>
              requestCancelation(CancelationSignal.External) *> state.read.void
          }
        }
  }
}
