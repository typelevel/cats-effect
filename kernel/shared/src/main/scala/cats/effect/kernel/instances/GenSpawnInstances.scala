/*
 * Copyright 2020-2021 Typelevel
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

package cats.effect.kernel.instances

import cats.{~>, Align, Applicative, CommutativeApplicative, Eval, Functor, Monad, Parallel}
import cats.data.Ior
import cats.effect.kernel.{GenSpawn, Outcome, ParallelF}
import cats.implicits._

trait GenSpawnInstances {

  implicit def parallelForGenSpawn[M[_], E](
      implicit M: GenSpawn[M, E]): Parallel.Aux[M, ParallelF[M, *]] =
    new Parallel[M] {
      type F[A] = ParallelF[M, A]

      def applicative: Applicative[F] = commutativeApplicativeForParallelF[M, E]

      def monad: Monad[M] = M

      def sequential: F ~> M =
        new (F ~> M) {
          def apply[A](fa: F[A]): M[A] = ParallelF.value[M, A](fa)
        }

      def parallel: M ~> F =
        new (M ~> F) {
          def apply[A](ma: M[A]): F[A] = ParallelF[M, A](ma)
        }

    }

  implicit def commutativeApplicativeForParallelF[F[_], E](
      implicit F: GenSpawn[F, E]): CommutativeApplicative[ParallelF[F, *]] =
    new CommutativeApplicative[ParallelF[F, *]] {

      final override def pure[A](a: A): ParallelF[F, A] = ParallelF(F.pure(a))

      final override def map2[A, B, Z](fa: ParallelF[F, A], fb: ParallelF[F, B])(
          f: (A, B) => Z): ParallelF[F, Z] =
        ParallelF(
          F.uncancelable { poll =>
            for {
              fiberA <- F.start(ParallelF.value(fa))
              fiberB <- F.start(ParallelF.value(fb))

              // start a pair of supervisors to ensure that the opposite is canceled on error
              _ <- F start {
                fiberB.join flatMap {
                  case Outcome.Succeeded(_) => F.unit
                  case _ => fiberA.cancel
                }
              }

              _ <- F start {
                fiberA.join flatMap {
                  case Outcome.Succeeded(_) => F.unit
                  case _ => fiberB.cancel
                }
              }

              a <- F
                .onCancel(poll(fiberA.join), bothUnit(fiberA.cancel, fiberB.cancel))
                .flatMap[A] {
                  case Outcome.Succeeded(fa) =>
                    fa

                  case Outcome.Errored(e) =>
                    fiberB.cancel *> F.raiseError(e)

                  case Outcome.Canceled() =>
                    fiberB.cancel *> poll {
                      fiberB.join flatMap {
                        case Outcome.Succeeded(_) | Outcome.Canceled() =>
                          F.canceled *> F.never
                        case Outcome.Errored(e) =>
                          F.raiseError(e)
                      }
                    }
                }

              z <- F.onCancel(poll(fiberB.join), fiberB.cancel).flatMap[Z] {
                case Outcome.Succeeded(fb) =>
                  fb.map(b => f(a, b))

                case Outcome.Errored(e) =>
                  F.raiseError(e)

                case Outcome.Canceled() =>
                  poll {
                    fiberA.join flatMap {
                      case Outcome.Succeeded(_) | Outcome.Canceled() =>
                        F.canceled *> F.never
                      case Outcome.Errored(e) =>
                        F.raiseError(e)
                    }
                  }
              }
            } yield z
          }
        )

      final override def map2Eval[A, B, Z](fa: ParallelF[F, A], fb: Eval[ParallelF[F, B]])(
          f: (A, B) => Z): Eval[ParallelF[F, Z]] =
        Eval.now(
          ParallelF(
            F.uncancelable { poll =>
              for {
                fiberA <- F.start(ParallelF.value(fa))
                fiberB <- F.start(ParallelF.value(fb.value))

                // start a pair of supervisors to ensure that the opposite is canceled on error
                _ <- F start {
                  fiberB.join flatMap {
                    case Outcome.Succeeded(_) => F.unit
                    case _ => fiberA.cancel
                  }
                }

                _ <- F start {
                  fiberA.join flatMap {
                    case Outcome.Succeeded(_) => F.unit
                    case _ => fiberB.cancel
                  }
                }

                a <- F
                  .onCancel(poll(fiberA.join), bothUnit(fiberA.cancel, fiberB.cancel))
                  .flatMap[A] {
                    case Outcome.Succeeded(fa) =>
                      fa

                    case Outcome.Errored(e) =>
                      fiberB.cancel *> F.raiseError(e)

                    case Outcome.Canceled() =>
                      fiberB.cancel *> poll {
                        fiberB.join flatMap {
                          case Outcome.Succeeded(_) | Outcome.Canceled() =>
                            F.canceled *> F.never
                          case Outcome.Errored(e) =>
                            F.raiseError(e)
                        }
                      }
                  }

                z <- F.onCancel(poll(fiberB.join), fiberB.cancel).flatMap[Z] {
                  case Outcome.Succeeded(fb) =>
                    fb.map(b => f(a, b))

                  case Outcome.Errored(e) =>
                    F.raiseError(e)

                  case Outcome.Canceled() =>
                    poll {
                      fiberA.join flatMap {
                        case Outcome.Succeeded(_) | Outcome.Canceled() =>
                          F.canceled *> F.never
                        case Outcome.Errored(e) =>
                          F.raiseError(e)
                      }
                    }
                }
              } yield z
            }
          )
        )

      final override def ap[A, B](ff: ParallelF[F, A => B])(
          fa: ParallelF[F, A]): ParallelF[F, B] =
        map2(ff, fa)(_(_))

      final override def product[A, B](
          fa: ParallelF[F, A],
          fb: ParallelF[F, B]): ParallelF[F, (A, B)] =
        map2(fa, fb)((_, _))

      final override def map[A, B](fa: ParallelF[F, A])(f: A => B): ParallelF[F, B] =
        ParallelF(ParallelF.value(fa).map(f))

      final override def unit: ParallelF[F, Unit] =
        ParallelF(F.unit)

      // assumed to be uncancelable
      private[this] def bothUnit(a: F[Unit], b: F[Unit]): F[Unit] =
        F.start(a).flatMap(f => b *> f.join.void)
    }

  implicit def alignForParallelF[F[_], E](implicit F: GenSpawn[F, E]): Align[ParallelF[F, *]] =
    new Align[ParallelF[F, *]] {

      override def functor: Functor[ParallelF[F, *]] = commutativeApplicativeForParallelF[F, E]

      override def align[A, B](
          fa: ParallelF[F, A],
          fb: ParallelF[F, B]): ParallelF[F, Ior[A, B]] =
        alignWith(fa, fb)(identity)

      override def alignWith[A, B, C](fa: ParallelF[F, A], fb: ParallelF[F, B])(
          f: Ior[A, B] => C): ParallelF[F, C] =
        ParallelF(
          (ParallelF.value(fa).attempt, ParallelF.value(fb).attempt)
            .parMapN((ea, eb) => catsStdInstancesForEither.alignWith(ea, eb)(f))
            .flatMap(F.fromEither)
        )

    }
}
