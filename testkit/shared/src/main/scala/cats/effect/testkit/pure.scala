/*
 * Copyright 2020 Typelevel
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

package cats.effect.testkit

import cats.{~>, Eq, Functor, Id, Monad, MonadError, Show}
import cats.data.{Kleisli, WriterT}
import cats.effect.kernel._
import cats.free.FreeT
import cats.implicits._

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

  final case class FiberCtx[E](self: PureFiber[E, _], masks: List[MaskId] = Nil)

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
        val state = state0[Main]

        MVar[Main, List[Finalizer[E]]](Nil).flatMap { finalizers =>
          val fiber = new PureFiber[E, A](state0, finalizers)

          val identified = canceled.mapF { ta =>
            val fk = new (FiberR[E, *] ~> IdOC[E, *]) {
              def apply[a](ke: FiberR[E, a]) =
                ke.run(FiberCtx(fiber))
            }

            ta.mapK(fk)
          }

          import Outcome._

          val body = identified.flatMap(a =>
            state.tryPut(Completed(a.pure[PureConc[E, *]]))) handleErrorWith { e =>
            state.tryPut(Errored(e))
          }

          val results = state.read.flatMap {
            case Canceled() => (Outcome.Canceled(): IdOC[E, A]).pure[Main]
            case Errored(e) => (Outcome.Errored(e): IdOC[E, A]).pure[Main]

            case Completed(fa) =>
              val identifiedCompletion = fa.mapF { ta =>
                val fk = new (FiberR[E, *] ~> IdOC[E, *]) {
                  def apply[a](ke: FiberR[E, a]) =
                    ke.run(FiberCtx(fiber))
                }

                ta.mapK(fk)
              }

              identifiedCompletion.map(a => Completed[Id, E, A](a): IdOC[E, A]) handleError {
                e =>
                  Errored(e)
              }
          }

          Kleisli.ask[ResolvedPC[E, *], MVar.Universe].map { u =>
            body.run(u) >> results.run(u)
          }
        }
      }
    }

    backM.flatten
  }

  /**
   * Produces Completed(None) when the main fiber is deadlocked. Note that
   * deadlocks outside of the main fiber are ignored when results are
   * appropriately produced (i.e. daemon semantics).
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
      case (_, false) => Outcome.Completed(None)

      // we could make a writer that only receives one object, but that seems meh. just pretend we deadlocked
      case _ => Outcome.Completed(None)
    }
  }

  // the one in Free is broken: typelevel/cats#3240
  implicit def catsFreeMonadErrorForFreeT2[S[_], M[_], E](
      implicit E: MonadError[M, E],
      S: Functor[S]): MonadError[FreeT[S, M, *], E] =
    new MonadError[FreeT[S, M, *], E] {
      private val F = FreeT.catsFreeMonadErrorForFreeT2[S, M, E]

      def pure[A](x: A): FreeT[S, M, A] =
        F.pure(x)

      def flatMap[A, B](fa: FreeT[S, M, A])(f: A => FreeT[S, M, B]): FreeT[S, M, B] =
        F.flatMap(fa)(f)

      def tailRecM[A, B](a: A)(f: A => FreeT[S, M, Either[A, B]]): FreeT[S, M, B] =
        F.tailRecM(a)(f)

      // this is the thing we need to override
      def handleErrorWith[A](fa: FreeT[S, M, A])(f: E => FreeT[S, M, A]) = {
        val ft = FreeT.liftT[S, M, FreeT[S, M, A]] {
          val resultsM = fa.resume.map {
            case Left(se) =>
              pure(()).flatMap(_ => FreeT.roll(se.map(handleErrorWith(_)(f))))

            case Right(a) =>
              pure(a)
          }

          resultsM.handleErrorWith { e =>
            f(e).resume.map { eth =>
              FreeT.defer(eth.swap.pure[M]) // why on earth is defer inconsistent with resume??
            }
          }
        }

        ft.flatMap(identity)
      }

      def raiseError[A](e: E) =
        F.raiseError(e)
    }

  implicit def concurrentForPureConc[E]: Concurrent[PureConc[E, *], E] =
    new Concurrent[PureConc[E, *], E] {
      private[this] val M: MonadError[PureConc[E, *], E] =
        Kleisli.catsDataMonadErrorForKleisli

      private[this] val Thread = ApplicativeThread[PureConc[E, *]]

      def pure[A](x: A): PureConc[E, A] =
        M.pure(x)

      def handleErrorWith[A](fa: PureConc[E, A])(f: E => PureConc[E, A]): PureConc[E, A] =
        M.handleErrorWith(fa)(f)

      def raiseError[A](e: E): PureConc[E, A] =
        M.raiseError(e)

      def onCancel[A](fa: PureConc[E, A], fin: PureConc[E, Unit]): PureConc[E, A] =
        withCtx(_.self.pushFinalizer(fin.attempt.void) *> fa)

      def canceled: PureConc[E, Unit] =
        withCtx { ctx =>
          if (ctx.masks.isEmpty)
            ctx.self.cancel >> ctx.self.runFinalizers >> Thread.done
          else
            ctx.self.cancel
        }

      def cede: PureConc[E, Unit] =
        Thread.cede

      def never[A]: PureConc[E, A] =
        Thread.done[A]

      private def startOne[Result](parentMasks: List[MaskId])(
          foldResult: Outcome[Id, E, Result] => PureConc[E, Unit])
          : StartOnePartiallyApplied[Result] =
        new StartOnePartiallyApplied(parentMasks, foldResult)

      // Using the partially applied pattern to defer the choice of L/R
      final class StartOnePartiallyApplied[Result](
          parentMasks: List[MaskId],
          foldResult: Outcome[Id, E, Result] => PureConc[E, Unit]
      ) {
        // we play careful tricks here to forward the masks on from the parent to the child
        // this is necessary because start drops masks
        def apply[L, OtherFiber](
            that: PureConc[E, L],
            getOtherFiber: PureConc[E, OtherFiber]
        )(
            toResult: (L, OtherFiber) => Result
        ): PureConc[E, L] =
          withCtx { (ctx2: FiberCtx[E]) =>
            val body = bracketCase(unit)(_ => that) {
              case (_, Outcome.Completed(fa)) =>
                // we need to do special magic to make cancelation distribute over race analogously to uncancelable
                ctx2
                  .self
                  .canceled
                  .ifM(
                    foldResult(Outcome.Canceled()),
                    for {
                      a <- fa
                      fiberB <- getOtherFiber
                      _ <- foldResult(Outcome.Completed[Id, E, Result](toResult(a, fiberB)))
                    } yield ()
                  )

              case (_, Outcome.Errored(e)) =>
                foldResult(Outcome.Errored(e))

              case (_, Outcome.Canceled()) =>
                foldResult(Outcome.Canceled())
            }

            localCtx(ctx2.copy(masks = ctx2.masks ::: parentMasks), body)
          }
      }

      /**
       * Whereas `start` ignores the cancelability of the parent fiber
       * when forking off the child, `racePair` inherits cancelability.
       * Thus, `uncancelable(_ => race(fa, fb)) <-> race(uncancelable(_ => fa), uncancelable(_ => fb))`,
       * while `uncancelable(_ => start(fa)) <-> start(fa)`.
       *
       * race(cede >> raiseError(e1), raiseError(e2)) <-> raiseError(e1)
       * race(raiseError(e1), cede >> raiseError(e2)) <-> raiseError(e2)
       * race(canceled, raiseError(e)) <-> raiseError(e)
       * race(raiseError(e), canceled) <-> raiseError(e)
       */
      def racePair[A, B](fa: PureConc[E, A], fb: PureConc[E, B]): PureConc[
        E,
        Either[(A, Fiber[PureConc[E, *], E, B]), (Fiber[PureConc[E, *], E, A], B)]] =
        withCtx { (ctx: FiberCtx[E]) =>
          type Result =
            Either[(A, Fiber[PureConc[E, *], E, B]), (Fiber[PureConc[E, *], E, A], B)]

          for {
            results0 <- MVar.empty[PureConc[E, *], Outcome[Id, E, Result]]
            results = results0[PureConc[E, *]]

            fiberAVar0 <- MVar.empty[PureConc[E, *], Fiber[PureConc[E, *], E, A]]
            fiberBVar0 <- MVar.empty[PureConc[E, *], Fiber[PureConc[E, *], E, B]]

            fiberAVar = fiberAVar0[PureConc[E, *]]
            fiberBVar = fiberBVar0[PureConc[E, *]]

            cancelVar0 <- MVar.empty[PureConc[E, *], Unit]
            errorVar0 <- MVar.empty[PureConc[E, *], E]

            cancelVar = cancelVar0[PureConc[E, *]]
            errorVar = errorVar0[PureConc[E, *]]

            completeWithError = (e: E) => results.tryPut(Outcome.Errored(e)).void // last wins
            completeWithCancel = results.tryPut(Outcome.Canceled()).void

            cancelReg = cancelVar.tryRead.flatMap {
              case Some(_) =>
                completeWithCancel // the other one has canceled, so cancel the whole

              case None =>
                cancelVar
                  .tryPut(())
                  .ifM( // we're the first to cancel
                    errorVar.tryRead flatMap {
                      case Some(e) =>
                        completeWithError(
                          e
                        ) // ...because the other one errored, so use that error
                      case None => unit // ...because the other one is still in progress
                    },
                    completeWithCancel
                  ) // race condition happened and both are now canceled
            }

            errorReg = { (e: E) =>
              errorVar.tryRead.flatMap {
                case Some(_) =>
                  completeWithError(e) // both have errored, use the last one (ours)

                case None =>
                  errorVar
                    .tryPut(e)
                    .ifM( // we were the first to error
                      cancelVar.tryRead flatMap {
                        case Some(_) =>
                          completeWithError(e) // ...because the other one canceled, so use ours
                        case None => unit // ...because the other one is still in progress
                      },
                      completeWithError(e)
                    ) // both have errored, there was a race condition, and we were the loser (use our error)
              }
            }

            resultReg: (Outcome[Id, E, Result] => PureConc[E, Unit]) = _.fold(
              cancelReg,
              errorReg,
              result => results.tryPut(Outcome.Completed(result)).void
            )

            start0 = startOne[Result](ctx.masks)(resultReg)

            fa2 = start0(fa, fiberBVar.read) { (a, fiberB) => Left((a, fiberB)) }

            fb2 = start0(fb, fiberAVar.read) { (b, fiberA) => Right((fiberA, b)) }

            back <- uncancelable { poll =>
              for {
                // note that we're uncancelable here, but we captured the masks *earlier* so we forward those along, ignoring this one
                fiberA <- start(fa2)
                fiberB <- start(fb2)

                _ <- fiberAVar.put(fiberA)
                _ <- fiberBVar.put(fiberB)

                backOC <- onCancel(poll(results.read), fiberA.cancel >> fiberB.cancel)

                back <- backOC match {
                  case Outcome.Completed(res) =>
                    pure(res)

                  case Outcome.Errored(e) =>
                    raiseError[Result](e)

                  /*
                   * This is REALLY tricky, but poll isn't enough here. For example:
                   *
                   * uncancelable(p => racePair(p(canceled), p(canceled))) <-> canceled
                   *
                   * This semantic is pretty natural, but we can't do it here without
                   * directly manipulating the masks because we don't have the outer poll!
                   * To solve this problem, we just nuke the masks and forcibly self-cancel.
                   * We don't really have to worry about nesting problems here because, if
                   * our children were somehow able to cancel, then whatever combination of
                   * masks exists must have all been polled away *there*, so we can pretend
                   * that they were similarly polled here.
                   */
                  case Outcome.Canceled() =>
                    localCtx(ctx.copy(masks = Nil), canceled >> never[Result])
                }
              } yield back
            }
          } yield back
        }

      def start[A](fa: PureConc[E, A]): PureConc[E, Fiber[PureConc[E, *], E, A]] =
        MVar.empty[PureConc[E, *], Outcome[PureConc[E, *], E, A]].flatMap { state =>
          MVar[PureConc[E, *], List[Finalizer[E]]](Nil).flatMap { finalizers =>
            val fiber = new PureFiber[E, A](state, finalizers)
            val identified = localCtx(FiberCtx(fiber), fa) // note we drop masks here

            // the tryPut(s) here are interesting: they encode first-wins semantics on cancelation/completion
            val body = identified.flatMap(a =>
              state.tryPut[PureConc[E, *]](
                Outcome.Completed(a.pure[PureConc[E, *]]))) handleErrorWith { e =>
              state.tryPut[PureConc[E, *]](Outcome.Errored(e))
            }

            Thread.start(body).as(fiber)
          }
        }

      def uncancelable[A](
          body: PureConc[E, *] ~> PureConc[E, *] => PureConc[E, A]): PureConc[E, A] = {
        val mask = new MaskId

        val poll = new (PureConc[E, *] ~> PureConc[E, *]) {
          def apply[a](fa: PureConc[E, a]) =
            withCtx { ctx =>
              val ctx2 = ctx.copy(masks = ctx.masks.dropWhile(mask === _))

              // we need to explicitly catch and suppress errors here to allow cancelation to dominate
              val handled = fa handleErrorWith { e =>
                ctx
                  .self
                  .canceled
                  .ifM(
                    never, // if we're canceled, convert errors into non-termination (we're canceling anyway)
                    raiseError(e))
              }

              localCtx(ctx2, handled)
            }
        }

        withCtx { ctx =>
          val ctx2 = ctx.copy(masks = mask :: ctx.masks)

          localCtx(ctx2, body(poll)) <*
            ctx
              .self
              .canceled
              .ifM(canceled, unit) // double-check cancelation whenever we exit a block
        }
      }

      def flatMap[A, B](fa: PureConc[E, A])(f: A => PureConc[E, B]): PureConc[E, B] =
        M.flatMap(fa)(f)

      def tailRecM[A, B](a: A)(f: A => PureConc[E, Either[A, B]]): PureConc[E, B] =
        M.tailRecM(a)(f)
    }

  implicit def eqPureConc[E: Eq, A: Eq]: Eq[PureConc[E, A]] = Eq.by(run(_))

  implicit def showPureConc[E: Show, A: Show]: Show[PureConc[E, A]] =
    Show show { pc =>
      val trace = ThreadT
        .prettyPrint(resolveMain(pc), limit = 1024)
        .fold("Canceled", e => s"Errored(${e.show})", str => str.replace('╭', '├'))

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

  final class PureFiber[E, A](
      state0: MVar[Outcome[PureConc[E, *], E, A]],
      finalizers0: MVar[List[PureConc[E, Unit]]])
      extends Fiber[PureConc[E, *], E, A] {

    private[this] val state = state0[PureConc[E, *]]
    private[this] val finalizers = finalizers0[PureConc[E, *]]

    private[pure] val canceled: PureConc[E, Boolean] =
      state.tryRead.map(_.map(_.fold(true, _ => false, _ => false)).getOrElse(false))

    private[pure] val realizeCancelation: PureConc[E, Boolean] = {
      val checkM = withCtx[E, Boolean](_.masks.isEmpty.pure[PureConc[E, *]])

      checkM.ifM(
        canceled.ifM(
          // if unmasked and canceled, finalize
          runFinalizers.as(true),
          // if unmasked but not canceled, ignore
          false.pure[PureConc[E, *]]
        ),
        // if masked, ignore cancelation state but retain until unmasked
        false.pure[PureConc[E, *]]
      )
    }

    private[pure] val runFinalizers: PureConc[E, Unit] =
      finalizers.take.flatMap(_.sequence_) >> finalizers.put(Nil)

    private[pure] def pushFinalizer(f: Finalizer[E]): PureConc[E, Unit] =
      finalizers.take.flatMap(fs => finalizers.put(f :: fs))

    private[pure] val popFinalizer: PureConc[E, Unit] =
      finalizers.tryTake.flatMap {
        case Some(fs) => finalizers.put(fs.drop(1))
        case None =>
          ().pure[PureConc[E, *]] // this happens when we're being evaluated *as a finalizer* (in the traverse_ above) and we attempt to self-pop
      }

    val cancel: PureConc[E, Unit] = state.tryPut(Outcome.Canceled()).void

    val join: PureConc[E, Outcome[PureConc[E, *], E, A]] =
      state.read
  }
}
