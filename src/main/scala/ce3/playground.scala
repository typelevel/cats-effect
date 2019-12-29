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

import cats.{~>, Id, MonadError}
import cats.data.{Kleisli, WriterT}
import cats.implicits._

import cats.mtl.ApplicativeAsk
import cats.mtl.implicits._

import coop.{ApplicativeThread, ThreadT, MVar}

object playground {

  type PureConc[E, A] =
    Kleisli[
      ThreadT[
        // everything inside here is Concurrent-specific effects
        Kleisli[
          ExitCase[
            Id,
            E,
            ?],
          PureFiber[E, _],    // self fiber
          ?],
        ?],
      MVar.Universe,
      A]

  /**
   * Produces Completed(None) when the main fiber is deadlocked. Note that
   * deadlocks outside of the main fiber are ignored when results are
   * appropriately produced (i.e. daemon semantics).
   */
  def run[E, A](pc: PureConc[E, A]): ExitCase[Option, E, A] = {
    val resolved = MVar resolve {
      // this is PureConc[E, ?] without the inner Kleisli
      type Main[X] = Kleisli[ThreadT[ExitCase[Id, E, ?], ?], MVar.Universe, X]

      MVar.empty[Main, ExitCase[PureConc[E, ?], E, A]] flatMap { state =>
        MVar[Main, Int](0) flatMap { finalizers =>
          val fiber = new PureFiber[E, A](state, finalizers)

          val identified = pc mapF { ta =>
            ta mapK λ[Kleisli[ExitCase[Id, E, ?], PureFiber[E, _], ?] ~> ExitCase[Id, E, ?]] { ke =>
              ke.run(fiber)
            }
          }

          val body = identified.flatMap(a => state.tryPut[Main](ExitCase.Completed(a.pure[PureConc[E, ?]]))) handleErrorWith { e =>
            state.tryPut[Main](ExitCase.Errored(e))
          }

          import ExitCase._
          val results = state.read[Main] flatMap {
            case Canceled => (Canceled: ExitCase[Id, E, A]).pure[Main]
            case Errored(e) => (Errored(e): ExitCase[Id, E, A]).pure[Main]

            case Completed(fa) =>
              val identified = fa mapF { ta =>
                ta mapK λ[Kleisli[ExitCase[Id, E, ?], PureFiber[E, _], ?] ~> ExitCase[Id, E, ?]] { ke =>
                  ke.run(fiber)
                }
              }

              identified.map(a => Completed[Id, A](a): ExitCase[Id, E, A])
          }

          Kleisli.ask[ThreadT[ExitCase[Id, E, ?], ?], MVar.Universe] map { u =>
            (body.run(u), results.run(u))
          }
        }
      }
    }

    val scheduled = ThreadT roundRobin {
      // we put things into WriterT because roundRobin returns Unit
      val writerLift = λ[ExitCase[Id, E, ?] ~> WriterT[ExitCase[Id, E, ?], List[ExitCase[Id, E, A]], ?]](WriterT.liftF(_))
      val lifted = resolved.mapK(writerLift)

      lifted flatMap {
        case (body, results) =>
          body.mapK(writerLift) >> results.mapK(writerLift) flatMap { ec =>
            ThreadT liftF {
              WriterT.tell[ExitCase[Id, E, ?], List[ExitCase[Id, E, A]]](List(ec))
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

      def bracketCase[A, B](acquire: PureConc[E, A])(use: A => PureConc[E, B])(release: (A, ce3.ExitCase[PureConc[E, ?], E, B]) => PureConc[E, Unit]): PureConc[E, B] = ???

      def canceled[A](fallback: A): PureConc[E, A] =
        withSelf(_.cancelImmediate.ifM(Thread.done[A], pure(fallback)))

      def cede: PureConc[E, Unit] =
        Thread.cede

      def never[A]: PureConc[E, A] =
        Thread.done[A]

      def racePair[A, B](fa: PureConc[E, A], fb: PureConc[E, B]): PureConc[E, Either[(A, Fiber[PureConc[E, ?], E, B]), (Fiber[PureConc[E, ?], E, A], B)]] = ???

      def start[A](fa: PureConc[E, A]): PureConc[E, Fiber[PureConc[E, ?], E, A]] =
        PureFiber(fa) flatMap {
          case (fiber, body) => Thread.start(body).as(fiber)
        }

      def uncancelable[A](body: PureConc[E, ?] ~> PureConc[E, ?] => PureConc[E, A]): PureConc[E, A] = ???

      def flatMap[A, B](fa: PureConc[E, A])(f: A => PureConc[E, B]): PureConc[E, B] =
        M.flatMap(fa)(f)

      def tailRecM[A, B](a: A)(f: A => PureConc[E, Either[A, B]]): PureConc[E, B] =
        M.tailRecM(a)(f)

      // the type inferencer just... fails... completely here
      private[this] def withSelf[A](body: PureFiber[E, _] => PureConc[E, A]): PureConc[E, A] =
        Kleisli.liftF[ThreadT[Kleisli[ExitCase[Id, E, ?], PureFiber[E, _], ?], ?], MVar.Universe, PureConc[E, A]](
          ThreadT.liftF[Kleisli[ExitCase[Id, E, ?], PureFiber[E, _], ?], PureConc[E, A]](
            Kleisli.ask[ExitCase[Id, E, ?], PureFiber[E, _]].map(body))).flatten
        // ApplicativeAsk[PureConc[E, ?], PureFiber[E, _]].ask.flatMap(body)
    }

  final class PureFiber[E, A](
      state0: MVar[ExitCase[PureConc[E, ?], E, A]],
      finalizers0: MVar[Int])
      extends Fiber[PureConc[E, ?], E, A] {

    private[this] val state = state0[PureConc[E, ?]]
    private[this] val finalizers = finalizers0[PureConc[E, ?]]

    private[playground] val canceled: PureConc[E, Boolean] =
      state.tryRead.map(_.map(_.fold(true, _ => false, _ => false)).getOrElse(false))

    // note that this is atomic because, internally, we won't check cancelation here
    private[playground] val incrementFinalizers: PureConc[E, Unit] =
      finalizers.read.flatMap(i => finalizers.swap(i + 1)).void

    private[playground] val decrementFinalizers: PureConc[E, Unit] =
      finalizers.read.flatMap(i => finalizers.swap(i - 1)).void

    private[playground] val cancelImmediate: PureConc[E, Boolean] =
      state.tryPut(ExitCase.Canceled)

    val cancel: PureConc[E, Unit] =
      cancelImmediate >> finalizers.read.iterateUntil(_ <= 0).void

    val join: PureConc[E, ExitCase[PureConc[E, ?], E, A]] =
      state.read    // NB we don't await finalizers on join?
  }

  object PureFiber {

    def apply[E, A](fa: PureConc[E, A]): PureConc[E, (Fiber[PureConc[E, ?], E, A], PureConc[E, Unit])] =
      MVar.empty[PureConc[E, ?], ExitCase[PureConc[E, ?], E, A]] flatMap { state =>
        MVar[PureConc[E, ?], Int](0) map { finalizers =>
          val fiber = new PureFiber[E, A](state, finalizers)

          val identified = fa mapF { ta =>
            ta mapK λ[Kleisli[ExitCase[Id, E, ?], PureFiber[E, _], ?] ~> Kleisli[ExitCase[Id, E, ?], PureFiber[E, _], ?]] { ke =>
              // discard the parent fiber entirely
              Kleisli.liftF(ke.run(fiber))
            }
          }

          // the tryPut(s) here are interesting: they indicate that Cancelation dominates
          val body = identified.flatMap(a => state.tryPut[PureConc[E, ?]](ExitCase.Completed(a.pure[PureConc[E, ?]]))) handleErrorWith { e =>
            state.tryPut[PureConc[E, ?]](ExitCase.Errored(e))
          }

          (fiber, body.void)
        }
      }
  }
}
