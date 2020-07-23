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

import cats.{Eq, Eval, Monad, MonadError}
import cats.effect.kernel._
import cats.free.FreeT
import cats.implicits._

import scala.concurrent.duration._

object freeEval {

  type FreeSync[F[_], A] = FreeT[Eval, F, A]
  type FreeEitherSync[A] = FreeSync[Either[Throwable, *], A]

  def run[F[_]: Monad, A](ft: FreeT[Eval, F, A]): F[A] =
    ft.runM(_.value.pure[F])

  implicit def syncForFreeT[F[_]](
      implicit F: MonadError[F, Throwable]): Sync[FreeT[Eval, F, *]] =
    new Sync[FreeT[Eval, F, *]] {
      private[this] val M: MonadError[FreeT[Eval, F, *], Throwable] =
        cats.effect.testkit.pure.catsFreeMonadErrorForFreeT2

      def pure[A](x: A): FreeT[Eval, F, A] =
        M.pure(x)

      def handleErrorWith[A](fa: FreeT[Eval, F, A])(
          f: Throwable => FreeT[Eval, F, A]): FreeT[Eval, F, A] =
        M.handleErrorWith(fa)(f)

      def raiseError[A](e: Throwable): FreeT[Eval, F, A] =
        M.raiseError(e)

      def monotonic: FreeT[Eval, F, FiniteDuration] =
        delay(System.nanoTime().nanos)

      def realTime: FreeT[Eval, F, FiniteDuration] =
        delay(System.currentTimeMillis().millis)

      def flatMap[A, B](fa: FreeT[Eval, F, A])(f: A => FreeT[Eval, F, B]): FreeT[Eval, F, B] =
        fa.flatMap(f)

      def tailRecM[A, B](a: A)(f: A => FreeT[Eval, F, Either[A, B]]): FreeT[Eval, F, B] =
        M.tailRecM(a)(f)

      def delay[A](thunk: => A): FreeT[Eval, F, A] =
        FreeT.roll {
          Eval.always {
            try {
              pure(thunk)
            } catch {
              case t: Throwable =>
                raiseError(t)
            }
          }
        }

      def blocking[A](thunk: => A): FreeT[Eval, F, A] =
        delay(thunk)
    }

  implicit def eqFreeSync[F[_]: Monad, A](implicit F: Eq[F[A]]): Eq[FreeT[Eval, F, A]] =
    Eq.by(run(_))
}
