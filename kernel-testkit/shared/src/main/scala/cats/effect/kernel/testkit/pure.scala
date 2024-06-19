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

import cats.{~>, Applicative, Defer, Eq, Functor, Id, Monad, MonadError, Order, Show}
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

  final case class FiberCtx[E](
      self: PureFiber[E, ?],
      masks: List[MaskId] = Nil,
      finalizers: List[PureConc[E, Unit]] = Nil)

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
            .realizeCancelation
            .ifM(ApplicativeThread[PureConc[E, *]].done, ().pure[PureConc[E, *]])

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
        MVar.empty[Main, Unit] flatMap { canceled0 =>
          val state = state0[Main]
          val fiber = new PureFiber[E, A](state0, canceled0)

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

              identifiedCompletion.map(a => Succeeded[Id, E, A](a): IdOC[E, A]) handleError {
                e => Errored(e)
              }
          }

          Kleisli.ask[ResolvedPC[E, *], MVar.Universe].map { u =>
            ApplicativeThread[ResolvedPC[E, *]].start(body.run(u)) >> results.run(u)
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
            val ctx2 = ctx.copy(finalizers = fin.attempt.void :: ctx.finalizers)
            localCtx(ctx2, fa)
          }
        }

      def canceled: PureConc[E, Unit] =
        Thread.annotate("canceled")(withCtx(_.self.cancelAndRealize.ifM(Thread.done, unit)))

      def cede: PureConc[E, Unit] =
        Thread.cede

      def never[A]: PureConc[E, A] =
        withCtx[E, A] { ctx =>
          // we monitor for asynchronous cancelation. if we're masked, this won't cancel and we hang
          Thread.annotate("never")(ctx.self.awaitCancelation *> Thread.done)
        }

      def ref[A](a: A): PureConc[E, Ref[PureConc[E, *], A]] =
        MVar[PureConc[E, *], A](a).flatMap(mVar => Kleisli.pure(unsafeRef(mVar)))

      def deferred[A]: PureConc[E, Deferred[PureConc[E, *], A]] =
        MVar.empty[PureConc[E, *], A].flatMap(mVar => Kleisli.pure(unsafeDeferred(mVar)))

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
            withCtx { ctx =>
              // we need to race cancelation against reading the mvar
              // if cancelation wins, we shut down the thread
              MVar.empty[PureConc[E, *], Option[A]] flatMap { signal =>
                val left = Thread.start(ctx.self.awaitCancelation.ifM(signal.tryPut[PureConc[E, *]](None).void, unit))
                val right = Thread.start(mVar.read[PureConc[E, *]].flatMap(a => signal.tryPut[PureConc[E, *]](Some(a))))

                left *>
                  right *>
                  signal.read[PureConc[E, *]].flatMap(_.map(_.pure[PureConc[E, *]]).getOrElse(Thread.done))
              }
            }

          override def complete(a: A): PureConc[E, Boolean] = mVar.tryPut[PureConc[E, *]](a)

          override def tryGet: PureConc[E, Option[A]] = mVar.tryRead[PureConc[E, *]]
        }

      def start[A](fa: PureConc[E, A]): PureConc[E, Fiber[PureConc[E, *], E, A]] =
        Thread.annotate("start", true) {
          MVar.empty[PureConc[E, *], Outcome[PureConc[E, *], E, A]].flatMap { state =>
            MVar.empty[PureConc[E, *], Unit] flatMap { canceled =>
              val fiber = new PureFiber[E, A](state, canceled)

              // the tryPut here is interesting: it encodes first-wins semantics on cancelation/completion
              val body = guaranteeCase(fa)(state.tryPut[PureConc[E, *]](_).void)
              val identified = localCtx(FiberCtx(fiber), body)
              Thread.start(identified.attempt.void).as(fiber)
            }
          }
        }

      def uncancelable[A](body: Poll[PureConc[E, *]] => PureConc[E, A]): PureConc[E, A] =
        Thread.annotate("uncancelable", true) {
          val mask = new MaskId

          val poll = new Poll[PureConc[E, *]] {
            def apply[a](fa: PureConc[E, a]) =
              withCtx { ctx =>
                val ctx2 = ctx.copy(masks = ctx.masks.dropWhile(mask === _))
                localCtx(ctx2, fa.attempt <* ctx.self.realizeCancelation).rethrow
              }
          }

          withCtx { ctx =>
            val ctx2 = ctx.copy(masks = mask :: ctx.masks)
            localCtx(ctx2, body(poll))
          }
        }

      // we happen to know this is non-memoizing, so we're just using it as a shortcut
      def unique: PureConc[E, Unique.Token] =
        Defer[PureConc[E, *]].defer(pure(new Unique.Token()))

      def forceR[A, B](fa: PureConc[E, A])(fb: PureConc[E, B]): PureConc[E, B] =
        Thread.annotate("forceR")(productR(attempt(fa))(fb))

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
      val canceled0: MVar[Unit])
      extends Fiber[PureConc[E, *], E, A]
      with Serializable {

    private[this] val state = state0[PureConc[E, *]]

    private[pure] val canceled: PureConc[E, Boolean] =
      canceled0.tryRead[PureConc[E, *]].map(_.as(true).getOrElse(false))

    private[pure] val realizeCancelation: PureConc[E, Boolean] =
      withCtx { ctx =>
        val checkM = ctx.masks.isEmpty.pure[PureConc[E, *]]

        checkM.ifM(
          canceled.ifM(
            // if unmasked and canceled, finalize
            allocateForPureConc[E] uncancelable { _ =>
              ctx.finalizers.sequence_.as(true) <* state0.tryPut[PureConc[E, *]](
                Outcome.Canceled())
            },
            // if unmasked but not canceled, ignore
            false.pure[PureConc[E, *]]
          ),
          // if masked, ignore cancelation state but retain until unmasked
          false.pure[PureConc[E, *]]
        )
      }

    private[pure] val awaitCancelation: PureConc[E, Boolean] =
      canceled0.read[PureConc[E, *]] *> realizeCancelation

    private[pure] val cancelAndRealize: PureConc[E, Boolean] =
      canceled0.tryPut[PureConc[E, *]](()) *> realizeCancelation

    val join: PureConc[E, Outcome[PureConc[E, *], E, A]] = {
      val Thread = ApplicativeThread[PureConc[E, *]]

      withCtx { ctx =>
        // this is exactly like Deferred#get
        MVar.empty[PureConc[E, *], Option[Outcome[PureConc[E, *], E, A]]] flatMap { signal =>
          // note we must read our *own* canceled, not the target fiber's
          val left = Thread.start(ctx.self.awaitCancelation.ifM(signal.tryPut[PureConc[E, *]](None).void, Applicative[PureConc[E, *]].unit))
          val right = Thread.start(state.read.flatMap(oc => signal.tryPut[PureConc[E, *]](Some(oc))))

          left *> right *> signal.read[PureConc[E, *]].flatMap(_.map(_.pure[PureConc[E, *]]).getOrElse(Thread.done))
        }
      }
    }

    val cancel: PureConc[E, Unit] = canceled0.tryPut[PureConc[E, *]](()) *> join.void
  }
}
