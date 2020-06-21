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
import scala.concurrent.duration.{MILLISECONDS, NANOSECONDS, TimeUnit}

/**
 * Clock provides the current time, as a pure alternative to:
 *
 *  - Java's
 *    [[https://docs.oracle.com/javase/8/docs/api/java/lang/System.html#currentTimeMillis-- System.currentTimeMillis]]
 *    for getting the "real-time clock" and
 *    [[https://docs.oracle.com/javase/8/docs/api/java/lang/System.html#nanoTime-- System.nanoTime]]
 *    for a monotonic clock useful for time measurements
 *  - JavaScript's `Date.now()` and `performance.now()`
 *
 * `Clock` works with an `F` monadic context that can suspend
 * side effects (e.g. [[IO]]).
 *
 * This is NOT a type class, as it does not have the coherence
 * requirement.
 */
@implicitNotFound("""Cannot find an implicit value for Clock[${F}]:
* import an implicit Timer[${F}] in scope or
* create a Clock[${F}] instance with Clock.create
""")
trait Clock[F[_]] {

  /**
   * Returns the current time, as a Unix timestamp (number of time units
   * since the Unix epoch), suspended in `F[_]`.
   *
   * This is the pure equivalent of Java's `System.currentTimeMillis`,
   * or of `CLOCK_REALTIME` from Linux's `clock_gettime()`.
   *
   * The provided `TimeUnit` determines the time unit of the output,
   * its precision, but not necessarily its resolution, which is
   * implementation dependent. For example this will return the number
   * of milliseconds since the epoch:
   *
   * {{{
   *   import scala.concurrent.duration.MILLISECONDS
   *
   *   clock.realTime(MILLISECONDS)
   * }}}
   *
   * N.B. the resolution is limited by the underlying implementation
   * and by the underlying CPU and OS. If the implementation uses
   * `System.currentTimeMillis`, then it can't have a better
   * resolution than 1 millisecond, plus depending on underlying
   * runtime (e.g. Node.js) it might return multiples of 10
   * milliseconds or more.
   *
   * See [[monotonic]], for fetching a monotonic value that
   * may be better suited for doing time measurements.
   */
  def realTime(unit: TimeUnit): F[Long]

  /**
   * Returns a monotonic clock measurement, if supported by the
   * underlying platform.
   *
   * This is the pure equivalent of Java's `System.nanoTime`,
   * or of `CLOCK_MONOTONIC` from Linux's `clock_gettime()`.
   *
   * {{{
   *   clock.monotonic(NANOSECONDS)
   * }}}
   *
   * The returned value can have nanoseconds resolution and represents
   * the number of time units elapsed since some fixed but arbitrary
   * origin time. Usually this is the Unix epoch, but that's not
   * a guarantee, as due to the limits of `Long` this will overflow in
   * the future (2^63^ is about 292 years in nanoseconds) and the
   * implementation reserves the right to change the origin.
   *
   * The return value should not be considered related to wall-clock
   * time, the primary use-case being to take time measurements and
   * compute differences between such values, for example in order to
   * measure the time it took to execute a task.
   *
   * As a matter of implementation detail, the default `Clock[IO]`
   * implementation uses `System.nanoTime` and the JVM will use
   * `CLOCK_MONOTONIC` when available, instead of `CLOCK_REALTIME`
   * (see `clock_gettime()` on Linux) and it is up to the underlying
   * platform to implement it correctly.
   *
   * And be warned, there are platforms that don't have a correct
   * implementation of `CLOCK_MONOTONIC`. For example at the moment of
   * writing there is no standard way for such a clock on top of
   * JavaScript and the situation isn't so clear cut for the JVM
   * either, see:
   *
   *  - [[https://bugs.java.com/bugdatabase/view_bug.do?bug_id=6458294 bug report]]
   *  - [[http://cs.oswego.edu/pipermail/concurrency-interest/2012-January/008793.html concurrency-interest]]
   *    discussion on the X86 tsc register
   *
   * The JVM tries to do the right thing and at worst the resolution
   * and behavior will be that of `System.currentTimeMillis`.
   *
   * The recommendation is to use this monotonic clock when doing
   * measurements of execution time, or if you value monotonically
   * increasing values more than a correspondence to wall-time, or
   * otherwise prefer [[realTime]].
   */
  def monotonic(unit: TimeUnit): F[Long]
}

object Clock extends LowPriorityImplicits {
  def apply[F[_]](implicit ev: Clock[F]) = ev

  /**
   * Provides Clock instance for any `F` that has `Sync` defined
   */
  def create[F[_]](implicit F: Sync[F]): Clock[F] =
    new Clock[F] {
      override def realTime(unit: TimeUnit): F[Long] =
        F.delay(unit.convert(System.currentTimeMillis(), MILLISECONDS))

      override def monotonic(unit: TimeUnit): F[Long] =
        F.delay(unit.convert(System.nanoTime(), NANOSECONDS))
    }

  implicit class ClockOps[F[_]](val self: Clock[F]) extends AnyVal {

    /**
     * Modify the context `F` using transformation `f`.
     */
    def mapK[G[_]](f: F ~> G): Clock[G] = new Clock[G] {
      def realTime(unit: TimeUnit): G[Long] = f(self.realTime(unit))
      def monotonic(unit: TimeUnit): G[Long] = f(self.monotonic(unit))
    }
  }
}

protected[effect] trait LowPriorityImplicits extends LowerPriorityImplicits {

  /**
   * Derives a [[Clock]] instance for `cats.data.EitherT`,
   * given we have one for `F[_]`.
   */
  implicit def deriveEitherT[F[_], L](implicit F: Functor[F], clock: Clock[F]): Clock[EitherT[F, L, *]] =
    new Clock[EitherT[F, L, *]] {
      def realTime(unit: TimeUnit): EitherT[F, L, Long] =
        EitherT.liftF(clock.realTime(unit))

      def monotonic(unit: TimeUnit): EitherT[F, L, Long] =
        EitherT.liftF(clock.monotonic(unit))
    }

  /**
   * Derives a [[Clock]] instance for `cats.data.OptionT`,
   * given we have one for `F[_]`.
   */
  implicit def deriveOptionT[F[_]](implicit F: Functor[F], clock: Clock[F]): Clock[OptionT[F, *]] =
    new Clock[OptionT[F, *]] {
      def realTime(unit: TimeUnit): OptionT[F, Long] =
        OptionT.liftF(clock.realTime(unit))

      def monotonic(unit: TimeUnit): OptionT[F, Long] =
        OptionT.liftF(clock.monotonic(unit))
    }

  /**
   * Derives a [[Clock]] instance for `cats.data.StateT`,
   * given we have one for `F[_]`.
   */
  implicit def deriveStateT[F[_], S](implicit F: Applicative[F], clock: Clock[F]): Clock[StateT[F, S, *]] =
    new Clock[StateT[F, S, *]] {
      def realTime(unit: TimeUnit): StateT[F, S, Long] =
        StateT.liftF(clock.realTime(unit))

      def monotonic(unit: TimeUnit): StateT[F, S, Long] =
        StateT.liftF(clock.monotonic(unit))
    }

  /**
   * Derives a [[Clock]] instance for `cats.data.WriterT`,
   * given we have one for `F[_]`.
   */
  implicit def deriveWriterT[F[_], L](implicit F: Applicative[F],
                                      L: Monoid[L],
                                      clock: Clock[F]): Clock[WriterT[F, L, *]] =
    new Clock[WriterT[F, L, *]] {
      def realTime(unit: TimeUnit): WriterT[F, L, Long] =
        WriterT.liftF(clock.realTime(unit))

      def monotonic(unit: TimeUnit): WriterT[F, L, Long] =
        WriterT.liftF(clock.monotonic(unit))
    }

  /**
   * Derives a [[Clock]] instance for `cats.data.Kleisli`,
   * given we have one for `F[_]`.
   */
  implicit def deriveKleisli[F[_], R](implicit clock: Clock[F]): Clock[Kleisli[F, R, *]] =
    new Clock[Kleisli[F, R, *]] {
      def realTime(unit: TimeUnit): Kleisli[F, R, Long] =
        Kleisli.liftF(clock.realTime(unit))

      def monotonic(unit: TimeUnit): Kleisli[F, R, Long] =
        Kleisli.liftF(clock.monotonic(unit))
    }

  /**
   * Derives a [[Clock]] instance for `cats.data.IorT`,
   * given we have one for `F[_]`.
   */
  implicit def deriveIorT[F[_], L](implicit F: Applicative[F], clock: Clock[F]): Clock[IorT[F, L, *]] =
    new Clock[IorT[F, L, *]] {
      def realTime(unit: TimeUnit): IorT[F, L, Long] =
        IorT.liftF(clock.realTime(unit))

      def monotonic(unit: TimeUnit): IorT[F, L, Long] =
        IorT.liftF(clock.monotonic(unit))
    }

  /**
   * Derives a [[Clock]] instance for `cats.effect.Resource`,
   * given we have one for `F[_]`.
   */
  implicit def deriveResource[F[_]](implicit F: Applicative[F], clock: Clock[F]): Clock[Resource[F, *]] =
    new Clock[Resource[F, *]] {
      def realTime(unit: TimeUnit): Resource[F, Long] =
        Resource.liftF(clock.realTime(unit))

      def monotonic(unit: TimeUnit): Resource[F, Long] =
        Resource.liftF(clock.monotonic(unit))
    }
}

protected[effect] trait LowerPriorityImplicits {

  /**
   * Default implicit instance — given there's an implicit [[Timer]]
   * in scope, extracts a [[Clock]] instance from it.
   */
  implicit def extractFromTimer[F[_]](implicit timer: Timer[F]): Clock[F] =
    timer.clock
}
