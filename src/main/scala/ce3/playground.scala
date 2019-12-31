/*
 * Copyright 2019 Daniel Spiewak
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

package ce3

import cats.{~>, Eq, Functor, Id, Monad, MonadError}
import cats.data.{Kleisli, WriterT}
import cats.free.FreeT
import cats.implicits._

import cats.mtl.ApplicativeAsk
import cats.mtl.implicits._

import coop.{ApplicativeThread, ThreadT, MVar}

object playground {

  type IdEC[E, A] = ExitCase[Id, E, A]                      // a fiber may complete, error, or cancel
  type FiberR[E, A] = Kleisli[IdEC[E, ?], FiberCtx[E], A]   // fiber context and results
  type MVarR[F[_], A] = Kleisli[F, MVar.Universe, A]        // ability to use MVar(s)

  type PureConc[E, A] = MVarR[ThreadT[FiberR[E, ?], ?], A]

  type Finalizer[E] = ExitCase[PureConc[E, ?], E, Nothing] => PureConc[E, Unit]

  final class MaskId

  object MaskId {
    implicit val eq: Eq[MaskId] = Eq.fromUniversalEquals[MaskId]
  }

  final case class FiberCtx[E](self: PureFiber[E, _], masks: List[MaskId] = Nil)

  /**
   * Produces Completed(None) when the main fiber is deadlocked. Note that
   * deadlocks outside of the main fiber are ignored when results are
   * appropriately produced (i.e. daemon semantics).
   */
  def run[E, A](pc: PureConc[E, A]): ExitCase[Option, E, A] = {
    /*
     * The cancelation implementation is here. The failures of type inference make this look
     * HORRIBLE but the general idea is fairly simple: mapK over the FreeT into a new monad
     * which sequences a cancelation check within each flatten. Thus, we go from Kleisli[FreeT[Kleisli[ExitCase[Id, ...]]]]
     * to Kleisli[FreeT[Kleisli[FreeT[Kleisli[ExitCase[Id, ...]]]]]]]], which we then need to go
     * through and flatten. The cancelation check *itself* is in `cancelationCheck`, while the flattening
     * process is in the definition of `val canceled`.
     *
     * FlatMapK and TraverseK typeclasses would make this a one-liner.
     */

    val cancelationCheck = new (FiberR[E, ?] ~> PureConc[E, ?]) {
      def apply[α](ka: FiberR[E, α]): PureConc[E, α] = {
        val back = Kleisli.ask[IdEC[E, ?], FiberCtx[E]] map { ctx =>
          // we have to double check finalization so that we don't accidentally cancel the finalizer
          // this is also where we check masking to ensure that we don't abort out in the middle of a masked section
          (ctx.self.canceled, ctx.self.finalizing).mapN(_ && !_ && ctx.masks.isEmpty).ifM(
            ApplicativeThread[PureConc[E, ?]].done,
            mvarLiftF(ThreadT.liftF(ka)))
        }

        mvarLiftF(ThreadT.liftF(back)).flatten
      }
    }

    // flatMapF does something different
    val canceled = Kleisli { (u: MVar.Universe) =>
      val outerStripped = pc.mapF(_.mapK(cancelationCheck)).run(u)    // run the outer mvar kleisli
      val traversed = outerStripped.mapK(λ[PureConc[E, ?] ~> ThreadT[FiberR[E, ?], ?]](_.run(u)))    // run the inner mvar kleisli
      flattenK(traversed)
    }

    val resolved = MVar resolve {
      // this is PureConc[E, ?] without the inner Kleisli
      type Main[X] = MVarR[ThreadT[IdEC[E, ?], ?], X]

      MVar.empty[Main, ExitCase[PureConc[E, ?], E, A]] flatMap { state =>
        MVar[Main, List[Finalizer[E]]](Nil) flatMap { finalizers =>
          val fiber = new PureFiber[E, A](state, finalizers)

          val identified = canceled mapF { ta =>
            ta mapK λ[FiberR[E, ?] ~> IdEC[E, ?]] { ke =>
              ke.run(FiberCtx(fiber))
            }
          }

          val body = identified.flatMap(a => state.tryPut[Main](ExitCase.Completed(a.pure[PureConc[E, ?]]))) handleErrorWith { e =>
            state.tryPut[Main](ExitCase.Errored(e))
          }

          import ExitCase._
          val results = state.read[Main] flatMap {
            case Canceled => (Canceled: IdEC[E, A]).pure[Main]
            case Errored(e) => (Errored(e): IdEC[E, A]).pure[Main]

            case Completed(fa) =>
              val identified = fa mapF { ta =>
                ta mapK λ[FiberR[E, ?] ~> IdEC[E, ?]] { ke =>
                  ke.run(FiberCtx(fiber))
                }
              }

              identified.map(a => Completed[Id, A](a): IdEC[E, A])
          }

          Kleisli.ask[ThreadT[IdEC[E, ?], ?], MVar.Universe] map { u =>
            (body.run(u), results.run(u))
          }
        }
      }
    }

    val scheduled = ThreadT roundRobin {
      // we put things into WriterT because roundRobin returns Unit
      val writerLift = λ[IdEC[E, ?] ~> WriterT[IdEC[E, ?], List[IdEC[E, A]], ?]](WriterT.liftF(_))
      val lifted = resolved.mapK(writerLift)

      lifted flatMap {
        case (body, results) =>
          body.mapK(writerLift) >> results.mapK(writerLift) flatMap { ec =>
            ThreadT liftF {
              WriterT.tell[IdEC[E, ?], List[IdEC[E, A]]](List(ec))
            }
          }
      }
    }

    scheduled.run.mapK(λ[Id ~> Option](Some(_))) flatMap {
      case (List(results), _) => results.mapK(λ[Id ~> Option](Some(_)))
      case (_, false) => ExitCase.Completed(None)

      // we could make a writer that only receives one object, but that seems meh. just pretend we deadlocked
      case _ => ExitCase.Completed(None)
    }
  }

  implicit def concurrentBForPureConc[E]: ConcurrentBracket[PureConc[E, ?], E] =
    new Concurrent[PureConc[E, ?], E] with Bracket[PureConc[E, ?], E] {
      private[this] val M: MonadError[PureConc[E, ?], E] =
        Kleisli.catsDataMonadErrorForKleisli

      private[this] val Thread = ApplicativeThread[PureConc[E, ?]]

      def pure[A](x: A): PureConc[E, A] =
        M.pure(x)

      def handleErrorWith[A](fa: PureConc[E, A])(f: E => PureConc[E, A]): PureConc[E, A] =
        M.handleErrorWith(fa)(f)

      def raiseError[A](e: E): PureConc[E, A] =
        M.raiseError(e)

      def bracketCase[A, B](acquire: PureConc[E, A])(use: A => PureConc[E, B])(release: (A, ExitCase[PureConc[E, ?], E, B]) => PureConc[E, Unit]): PureConc[E, B] = {
        val init = uncancelable { _ =>
          acquire flatMap { a =>
            val finalizer: Finalizer[E] = ec => uncancelable(_ => release(a, ec) >> withCtx(_.self.popFinalizer))
            withCtx[E, Unit](_.self.pushFinalizer(finalizer)).as((a, finalizer))
          }
        }

        init flatMap {
          case (a, finalizer) =>
            val used = use(a) flatMap { b =>
              uncancelable { _ =>
                val released = release(a, ExitCase.Completed(b.pure[PureConc[E, ?]])) >>
                  withCtx(_.self.popFinalizer)

                released.as(b)
              }
            }

            handleErrorWith(used) { e =>
              finalizer(ExitCase.Errored(e)) >> raiseError(e)
            }
        }
      }

      def canceled[A](fallback: A): PureConc[E, A] =
        withCtx(_.self.cancelImmediate.ifM(Thread.done[A], pure(fallback)))

      def cede: PureConc[E, Unit] =
        Thread.cede

      def never[A]: PureConc[E, A] =
        Thread.done[A]

      def racePair[A, B](fa: PureConc[E, A], fb: PureConc[E, B]): PureConc[E, Either[(A, Fiber[PureConc[E, ?], E, B]), (Fiber[PureConc[E, ?], E, A], B)]] = ???

      def start[A](fa: PureConc[E, A]): PureConc[E, Fiber[PureConc[E, ?], E, A]] =
        MVar.empty[PureConc[E, ?], ExitCase[PureConc[E, ?], E, A]] flatMap { state =>
          MVar[PureConc[E, ?], List[Finalizer[E]]](Nil) flatMap { finalizers =>
            val fiber = new PureFiber[E, A](state, finalizers)
            val identified = localCtx(FiberCtx(fiber), fa)    // note we drop masks here

            // the tryPut(s) here are interesting: they encode first-wins semantics on cancelation/completion
            val body = identified.flatMap(a => state.tryPut[PureConc[E, ?]](ExitCase.Completed(a.pure[PureConc[E, ?]]))) handleErrorWith { e =>
              state.tryPut[PureConc[E, ?]](ExitCase.Errored(e))
            }

            Thread.start(body).as(fiber)
          }
        }

      def uncancelable[A](body: PureConc[E, ?] ~> PureConc[E, ?] => PureConc[E, A]): PureConc[E, A] = {
        val mask = new MaskId

        val poll = λ[PureConc[E, ?] ~> PureConc[E, ?]] { fa =>
          withCtx { ctx =>
            val ctx2 = ctx.copy(masks = ctx.masks.dropWhile(mask ===))
            localCtx(ctx2, fa)
          }
        }

        body(poll)
      }

      def flatMap[A, B](fa: PureConc[E, A])(f: A => PureConc[E, B]): PureConc[E, B] =
        M.flatMap(fa)(f)

      def tailRecM[A, B](a: A)(f: A => PureConc[E, Either[A, B]]): PureConc[E, B] =
        M.tailRecM(a)(f)
    }

  private[this] def mvarLiftF[F[_], A](fa: F[A]): MVarR[F, A] =
    Kleisli.liftF[F, MVar.Universe, A](fa)

  // this would actually be a very usful function for FreeT to have
  private[this] def flattenK[S[_]: Functor, M[_]: Monad, A](ft: FreeT[S, FreeT[S, M, ?], A]): FreeT[S, M, A] =
    ft.resume.flatMap(_.fold(sft => FreeT.liftF[S, M, FreeT[S, FreeT[S, M, ?], A]](sft).flatMap(flattenK(_)), FreeT.pure(_)))

  // the type inferencer just... fails... completely here
  private[this] def withCtx[E, A](body: FiberCtx[E] => PureConc[E, A]): PureConc[E, A] =
    mvarLiftF(ThreadT.liftF(Kleisli.ask[IdEC[E, ?], FiberCtx[E]].map(body))).flatten
    // ApplicativeAsk[PureConc[E, ?], FiberCtx[E]].ask.flatMap(body)

  private[this] def localCtx[E, A](ctx: FiberCtx[E], around: PureConc[E, A]): PureConc[E, A] =
    around mapF { ft =>
      ft mapK λ[FiberR[E, ?] ~> FiberR[E, ?]] { ka =>
        Kleisli(_ => ka.run(ctx))
      }
    }

  final class PureFiber[E, A](
      state0: MVar[ExitCase[PureConc[E, ?], E, A]],
      finalizers0: MVar[List[Finalizer[E]]])
      extends Fiber[PureConc[E, ?], E, A] {

    private[this] val state = state0[PureConc[E, ?]]
    private[this] val finalizers = finalizers0[PureConc[E, ?]]

    private[playground] val canceled: PureConc[E, Boolean] =
      state.tryRead.map(_.map(_.fold(true, _ => false, _ => false)).getOrElse(false))

    private[playground] val finalizing: PureConc[E, Boolean] =
      finalizers.tryRead.map(_.isEmpty)

    private[playground] val cancelImmediate: PureConc[E, Boolean] = {
      val checkM = withCtx[E, Boolean](_.masks.isEmpty.pure[PureConc[E, ?]])
      checkM.ifM(state.tryPut(ExitCase.Canceled), false.pure[PureConc[E, ?]])
    }

    private[playground] def pushFinalizer(f: Finalizer[E]): PureConc[E, Unit] =
      finalizers.take.flatMap(fs => finalizers.put(f :: fs))

    private[playground] val popFinalizer: PureConc[E, Unit] =
      finalizers.take.flatMap(fs => finalizers.put(fs.drop(1)))

    // in case of multiple simultaneous cancelations, we block all others while we traverse
    val cancel: PureConc[E, Unit] =
      cancelImmediate.ifM(
        finalizers.take.flatMap(_.traverse_(_(ExitCase.Canceled))) >> finalizers.put(Nil),
        finalizers.read.void)

    val join: PureConc[E, ExitCase[PureConc[E, ?], E, A]] =
      state.read
  }
}
