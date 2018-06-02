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

import cats.syntax.apply._
import cats.syntax.parallel._
import cats.{Applicative, Apply, MonadError, Monoid, Parallel, Semigroup}

import scala.util.control.NonFatal

/**
 * `Fiber` represents the (pure) result of an [[Async]] data type (e.g. [[IO]])
 * being started concurrently and that can be either joined or canceled.
 *
 * You can think of fibers as being lightweight threads, a fiber being a
 * concurrency primitive for doing cooperative multi-tasking.
 *
 * For example a `Fiber` value is the result of evaluating [[IO.start]]:
 *
 * {{{
 *   val io = IO.shift *> IO(println("Hello!"))
 *
 *   val fiber: IO[Fiber[IO, Unit]] = io.start
 * }}}
 *
 * Usage example:
 *
 * {{{
 *   for {
 *     fiber <- IO.shift *> launchMissiles.start
 *     _ <- runToBunker.handleErrorWith { error =>
 *       // Retreat failed, cancel launch (maybe we should
 *       // have retreated to our bunker before the launch?)
 *       fiber.cancel *> IO.raiseError(error)
 *     }
 *     aftermath <- fiber.join
 *   } yield {
 *     aftermath
 *   }
 * }}}
 */
trait Fiber[F[_], A] {
  /**
   * Triggers the cancellation of the fiber.
   *
   * Returns a new task that will complete when the cancellation is
   * sent (but not when it is observed or acted upon).
   *
   * Note that if the background process that's evaluating the result
   * of the underlying fiber is already complete, then there's nothing
   * to cancel.
   */
  def cancel: F[Unit]

  /**
   * Returns a new task that will await for the completion of the
   * underlying fiber, (asynchronously) blocking the current run-loop
   * until that result is available.
   */
  def join: F[A]
}

object Fiber extends FiberInstances {
  /**
   * Given a `join` and `cancel` tuple, builds a [[Fiber]] value.
   */
  def apply[F[_], A](join: F[A], cancel: F[Unit]): Fiber[F, A] =
    Tuple(join, cancel)

  private final case class Tuple[F[_], A](join: F[A], cancel: F[Unit])
    extends Fiber[F, A]
}

private[effect] abstract class FiberInstances extends FiberLowPriorityInstances {

  implicit def fiberApplicative[F[_], M[_]](implicit F: Parallel[F, M], G: MonadError[F, Throwable]): Applicative[Fiber[F, ?]] = new Applicative[Fiber[F, ?]] {
    final override def pure[A](x: A): Fiber[F, A] =
      Fiber(F.monad.pure(x), F.monad.unit)
    final override def ap[A, B](ff: Fiber[F, A => B])(fa: Fiber[F, A]): Fiber[F, B] =
      map2(ff, fa)(_(_))
    final override def map2[A, B, Z](fa: Fiber[F, A], fb: Fiber[F, B])(f: (A, B) => Z): Fiber[F, Z] =
      Fiber(
        (
          F.sequential(F.applicativeError.onError(F.parallel(fa.join))({case NonFatal(_) => F.parallel(fb.cancel)})),
          F.sequential(F.applicativeError.onError(F.parallel(fb.join))({case NonFatal(_) => F.parallel(fa.cancel)}))
        ).parMapN(f),
        fa.cancel *> fb.cancel)
    final override def product[A, B](fa: Fiber[F, A], fb: Fiber[F, B]): Fiber[F, (A, B)] =
      map2(fa, fb)((_, _))
    final override def map[A, B](fa: Fiber[F, A])(f: A => B): Fiber[F, B] =
      Fiber(F.monad.map(fa.join)(f), fa.cancel)
    final override val unit: Fiber[F, Unit] =
      Fiber(F.monad.unit, F.monad.unit)
  }

  implicit def fiberMonoid[F[_], M[_], A: Monoid](implicit F: Parallel[F, M], G: MonadError[F, Throwable]): Monoid[Fiber[F, A]] =
    Applicative.monoid[Fiber[F, ?], A]
}

private[effect] abstract class FiberLowPriorityInstances {
  implicit def fiberSemigroup[F[_], M[_], A: Semigroup](implicit F: Parallel[F, M], G: MonadError[F, Throwable]): Semigroup[Fiber[F, A]] =
    Apply.semigroup[Fiber[F, ?], A]
}