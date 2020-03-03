/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.{~>, Applicative, Apply, Monoid, Semigroup}

/**
 * `Fiber` represents the (pure) result of a [[Concurrent]] data type (e.g. [[IO]])
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
   * Returns a new task that will trigger the cancellation upon
   * evaluation. Depending on the implementation, this task might
   * await for all registered finalizers to finish, but this behavior
   * is implementation dependent.
   *
   * Note that if the background process that's evaluating the result
   * of the underlying fiber is already complete, then there's nothing
   * to cancel.
   */
  def cancel: CancelToken[F]

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
  def apply[F[_], A](join: F[A], cancel: CancelToken[F]): Fiber[F, A] =
    Tuple[F, A](join, cancel)

  final private case class Tuple[F[_], A](join: F[A], cancel: CancelToken[F]) extends Fiber[F, A]

  implicit class FiberOps[F[_], A](val self: Fiber[F, A]) extends AnyVal {

    /**
     * Modify the context `F` using transformation `f`.
     */
    def mapK[G[_]](f: F ~> G): Fiber[G, A] = new Fiber[G, A] {
      def cancel: CancelToken[G] = f(self.cancel)
      def join: G[A] = f(self.join)
    }
  }
}

abstract private[effect] class FiberInstances extends FiberLowPriorityInstances {
  implicit def fiberApplicative[F[_]](implicit F: Concurrent[F]): Applicative[Fiber[F, *]] =
    new Applicative[Fiber[F, *]] {
      final override def pure[A](x: A): Fiber[F, A] =
        Fiber(F.pure(x), F.unit)
      final override def ap[A, B](ff: Fiber[F, A => B])(fa: Fiber[F, A]): Fiber[F, B] =
        map2(ff, fa)(_(_))
      final override def map2[A, B, Z](fa: Fiber[F, A], fb: Fiber[F, B])(f: (A, B) => Z): Fiber[F, Z] = {
        val fa2 = F.guaranteeCase(fa.join) { case ExitCase.Error(_) => fb.cancel; case _ => F.unit }
        val fb2 = F.guaranteeCase(fb.join) { case ExitCase.Error(_) => fa.cancel; case _ => F.unit }
        Fiber(
          F.racePair(fa2, fb2).flatMap {
            case Left((a, fiberB))  => (a.pure[F], fiberB.join).mapN(f)
            case Right((fiberA, b)) => (fiberA.join, b.pure[F]).mapN(f)
          },
          F.map2(fa.cancel, fb.cancel)((_, _) => ())
        )
      }
      final override def product[A, B](fa: Fiber[F, A], fb: Fiber[F, B]): Fiber[F, (A, B)] =
        map2(fa, fb)((_, _))
      final override def map[A, B](fa: Fiber[F, A])(f: A => B): Fiber[F, B] =
        Fiber(F.map(fa.join)(f), fa.cancel)
      final override val unit: Fiber[F, Unit] =
        Fiber(F.unit, F.unit)
    }

  implicit def fiberMonoid[F[_]: Concurrent, M[_], A: Monoid]: Monoid[Fiber[F, A]] =
    Applicative.monoid[Fiber[F, *], A]
}

abstract private[effect] class FiberLowPriorityInstances {
  implicit def fiberSemigroup[F[_]: Concurrent, A: Semigroup]: Semigroup[Fiber[F, A]] =
    Apply.semigroup[Fiber[F, *], A]
}
