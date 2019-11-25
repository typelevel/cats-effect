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

import cats.{~>, Applicative, Functor, Monoid}
import cats.data._

import scala.annotation.implicitNotFound
import scala.concurrent.duration.FiniteDuration

/**
 * Timer is a scheduler of tasks.
 *
 * This is the purely functional equivalent of:
 *
 *  - Java's
 *    [[https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ScheduledExecutorService.html ScheduledExecutorService]]
 *  - JavaScript's
 *    [[https://developer.mozilla.org/en-US/docs/Web/API/WindowOrWorkerGlobalScope/setTimeout setTimeout]].
 *
 * It provides:
 *
 *  1. the ability to get the current time
 *  1. ability to delay the execution of a task with a specified time duration
 *
 * It does all of that in an `F` monadic context that can suspend
 * side effects and is capable of asynchronous execution (e.g. [[IO]]).
 *
 * This is NOT a type class, as it does not have the coherence
 * requirement.
 */
@implicitNotFound("""Cannot find an implicit value for Timer[${F}]:
* import Timer[${F}] from your effects library
* if using IO, use cats.effect.IOApp or build one with cats.effect.IO.timer
""")
trait Timer[F[_]] {

  /**
   * Returns a [[Clock]] instance associated with this timer
   * that can provide the current time and do time measurements.
   */
  def clock: Clock[F]

  /**
   * Creates a new task that will sleep for the given duration,
   * emitting a tick when that time span is over.
   *
   * As an example on evaluation this will print "Hello!" after
   * 3 seconds:
   *
   * {{{
   *   import cats.effect._
   *   import scala.concurrent.duration._
   *
   *   Timer[IO].sleep(3.seconds).flatMap { _ =>
   *     IO(println("Hello!"))
   *   }
   * }}}
   *
   * Note that `sleep` is required to introduce an asynchronous
   * boundary, even if the provided `timespan` is less or
   * equal to zero.
   */
  def sleep(duration: FiniteDuration): F[Unit]
}

object Timer {
  def apply[F[_]](implicit ev: Timer[F]): Timer[F] = ev

  /**
   * Derives a [[Timer]] instance for `cats.data.EitherT`,
   * given we have one for `F[_]`.
   */
  implicit def deriveEitherT[F[_], L](implicit F: Functor[F], timer: Timer[F]): Timer[EitherT[F, L, *]] =
    new Timer[EitherT[F, L, *]] {
      val clock: Clock[EitherT[F, L, *]] = Clock.deriveEitherT

      def sleep(duration: FiniteDuration): EitherT[F, L, Unit] =
        EitherT.liftF(timer.sleep(duration))
    }

  /**
   * Derives a [[Timer]] instance for `cats.data.OptionT`,
   * given we have one for `F[_]`.
   */
  implicit def deriveOptionT[F[_]](implicit F: Functor[F], timer: Timer[F]): Timer[OptionT[F, *]] =
    new Timer[OptionT[F, *]] {
      val clock: Clock[OptionT[F, *]] = Clock.deriveOptionT

      def sleep(duration: FiniteDuration): OptionT[F, Unit] =
        OptionT.liftF(timer.sleep(duration))
    }

  /**
   * Derives a [[Timer]] instance for `cats.data.WriterT`,
   * given we have one for `F[_]`.
   */
  implicit def deriveWriterT[F[_], L](implicit F: Applicative[F],
                                      L: Monoid[L],
                                      timer: Timer[F]): Timer[WriterT[F, L, *]] =
    new Timer[WriterT[F, L, *]] {
      val clock: Clock[WriterT[F, L, *]] = Clock.deriveWriterT

      def sleep(duration: FiniteDuration): WriterT[F, L, Unit] =
        WriterT.liftF(timer.sleep(duration))
    }

  /**
   * Derives a [[Timer]] instance for `cats.data.StateT`,
   * given we have one for `F[_]`.
   */
  implicit def deriveStateT[F[_], S](implicit F: Applicative[F], timer: Timer[F]): Timer[StateT[F, S, *]] =
    new Timer[StateT[F, S, *]] {
      val clock: Clock[StateT[F, S, *]] = Clock.deriveStateT

      def sleep(duration: FiniteDuration): StateT[F, S, Unit] =
        StateT.liftF(timer.sleep(duration))
    }

  /**
   * Derives a [[Timer]] instance for `cats.data.Kleisli`,
   * given we have one for `F[_]`.
   */
  implicit def deriveKleisli[F[_], R](implicit timer: Timer[F]): Timer[Kleisli[F, R, *]] =
    new Timer[Kleisli[F, R, *]] {
      val clock: Clock[Kleisli[F, R, *]] = Clock.deriveKleisli

      def sleep(duration: FiniteDuration): Kleisli[F, R, Unit] =
        Kleisli.liftF(timer.sleep(duration))
    }

  /**
   * Derives a [[Timer]] instance for `cats.data.IorT`,
   * given we have one for `F[_]`.
   */
  implicit def deriveIorT[F[_], L](implicit F: Applicative[F], timer: Timer[F]): Timer[IorT[F, L, *]] =
    new Timer[IorT[F, L, *]] {
      val clock: Clock[IorT[F, L, *]] = Clock.deriveIorT

      def sleep(duration: FiniteDuration): IorT[F, L, Unit] =
        IorT.liftF(timer.sleep(duration))
    }

  /**
   * Derives a [[Timer]] instance for `cats.effect.Resource`,
   * given we have one for `F[_]`.
   */
  implicit def deriveResource[F[_]](implicit F: Applicative[F], timer: Timer[F]): Timer[Resource[F, *]] =
    new Timer[Resource[F, *]] {
      val clock: Clock[Resource[F, *]] = Clock.deriveResource

      def sleep(duration: FiniteDuration): Resource[F, Unit] =
        Resource.liftF(timer.sleep(duration))
    }

  implicit class TimerOps[F[_]](val self: Timer[F]) extends AnyVal {

    /**
     * Modify the context `F` using transformation `f`.
     */
    def mapK[G[_]](f: F ~> G): Timer[G] = new Timer[G] {
      val clock: Clock[G] = self.clock.mapK(f)
      def sleep(duration: FiniteDuration): G[Unit] = f(self.sleep(duration))
    }
  }
}
