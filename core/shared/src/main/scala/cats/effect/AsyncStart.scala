/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

package cats.effect

import cats.data.{EitherT, OptionT, StateT, WriterT}
import cats.kernel.Monoid
import cats.syntax.all._
import simulacrum._

/**
 * Type-class describing concurrent execution via the `start` operation.
 *
 * The `start` operation returns a [[Fiber]] that can be attached
 * to a process that runs concurrently and that can be joined, in order
 * to wait for its result or that can be cancelled at a later time,
 * in case of a race condition.
 */
@typeclass
trait AsyncStart[F[_]] extends Async[F] {
  /**
   * Start concurrent execution of the source suspended in the `F` context.
   *
   * Returns a [[Fiber]] that can be used to either join or cancel
   * the running computation, being similar in spirit (but not
   * in implementation) to starting a thread.
   */
  def start[A](fa: F[A]): F[Fiber[F, A]]
}

object AsyncStart {
  /**
   * [[AsyncStart]] instance built for `cats.data.EitherT` values initialized
   * with any `F` data type that also implements `AsyncStart`.
   */
  implicit def catsEitherTAsyncStart[F[_]: AsyncStart, L]: AsyncStart[EitherT[F, L, ?]] =
    new EitherTAsyncStart[F, L] { def F = AsyncStart[F] }

  /**
   * [[AsyncStart]] instance built for `cats.data.OptionT` values initialized
   * with any `F` data type that also implements `AsyncStart`.
   */
  implicit def catsOptionTAsyncStart[F[_]: AsyncStart]: AsyncStart[OptionT[F, ?]] =
    new OptionTAsyncStart[F] { def F = AsyncStart[F] }

  /**
   * [[AsyncStart]] instance built for `cats.data.StateT` values initialized
   * with any `F` data type that also implements `AsyncStart`.
   */
  implicit def catsStateTAsync[F[_]: AsyncStart, S]: AsyncStart[StateT[F, S, ?]] =
    new StateTAsyncStart[F, S] { def F = AsyncStart[F] }

  /**
   * [[AsyncStart]] instance built for `cats.data.WriterT` values initialized
   * with any `F` data type that also implements `AsyncStart`.
   */
  implicit def catsWriterTAsync[F[_]: AsyncStart, L: Monoid]: AsyncStart[WriterT[F, L, ?]] =
    new WriterTAsyncStart[F, L] { def F = AsyncStart[F]; def L = Monoid[L] }

  private[effect] trait EitherTAsyncStart[F[_], L] extends Async.EitherTAsync[F, L]
    with AsyncStart[EitherT[F, L, ?]] {

    override protected implicit def F: AsyncStart[F]
    override protected def FF = F

    def start[A](fa: EitherT[F, L, A]) =
      EitherT.liftF(
        F.start(fa.value).map { fiber =>
          Fiber(
            EitherT(fiber.join),
            EitherT.liftF(fiber.cancel))
        })
  }

  private[effect] trait OptionTAsyncStart[F[_]] extends Async.OptionTAsync[F]
    with AsyncStart[OptionT[F, ?]] {

    override protected implicit def F: AsyncStart[F]
    override protected def FF = F

    def start[A](fa: OptionT[F, A]) = {
      OptionT.liftF(
        F.start(fa.value).map { fiber =>
          Fiber(OptionT(fiber.join), OptionT.liftF(fiber.cancel))
        })
    }
  }

  private[effect] trait StateTAsyncStart[F[_], S] extends Async.StateTAsync[F, S]
    with AsyncStart[StateT[F, S, ?]] {

    override protected implicit def F: AsyncStart[F]
    override protected def FA = F

    override def start[A](fa: StateT[F, S, A]) =
      StateT(start => F.start(fa.run(start)).map { fiber =>
        (start, Fiber(
          StateT(_ => fiber.join),
          StateT.liftF(fiber.cancel)))
      })
  }

  private[effect] trait WriterTAsyncStart[F[_], L] extends Async.WriterTAsync[F, L]
    with AsyncStart[WriterT[F, L, ?]] {

    override protected implicit def F: AsyncStart[F]
    override protected def FA = F

    def start[A](fa: WriterT[F, L, A]) =
      WriterT(F.start(fa.run).map { fiber =>
        (L.empty, Fiber(
          WriterT(fiber.join),
          WriterT.liftF(fiber.cancel)(L, F)))
      })
  }
}