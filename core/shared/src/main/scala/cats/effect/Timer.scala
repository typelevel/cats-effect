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

import scala.annotation.implicitNotFound
import scala.concurrent.duration.{FiniteDuration, TimeUnit}

/**
 * Timer is a scheduler of tasks.
 *
 * This is the purely functional equivalent of:
 *
 *  - Java's
 *    [[https://docs.oracle.com/javase/9/docs/api/java/util/concurrent/ScheduledExecutorService.html ScheduledExecutorService]]
 *  - JavaScript's
 *    [[https://developer.mozilla.org/en-US/docs/Web/API/WindowOrWorkerGlobalScope/setTimeout setTimeout]].
 *
 * It provides:
 *
 *  1. the ability to get the current time
 *  1. thread / call-stack shifting
 *  1. ability to delay the execution of a task with a specified time duration
 *
 * It does all of that in an `F` monadic context that can suspend
 * side effects and is capable of asynchronous execution (e.g. [[IO]]).
 *
 * This is NOT a type-class, as it does not have the coherence
 * requirement.
 */
@implicitNotFound("""Cannot find implicit value for Timer[${F}].
Note that ${F} needs to be a cats.effect.Async data type. You might also
need a scala.concurrent.ExecutionContext in scope, or equivalent, try to
import scala.concurrent.ExecutionContext.Implicits.global
""")
trait Timer[F[_]] {
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
   *   timer.clockRealTime(MILLISECONDS)
   * }}}
   *
   * N.B. the resolution is limited by the underlying implementation
   * and by the underlying CPU and OS. If the implementation uses
   * `System.currentTimeMillis`, then it can't have a better
   * resolution than 1 millisecond, plus depending on underlying
   * runtime (e.g. Node.js) it might return multiples of 10
   * milliseconds or more.
   *
   * See [[clockMonotonic]], for fetching a monotonic value that
   * may be better suited for doing time measurements.
   */
  def clockRealTime(unit: TimeUnit): F[Long]

  /**
   * Returns a monotonic clock measurement, if supported by the
   * underlying platform.
   *
   * This is the pure equivalent of Java's `System.nanoTime`,
   * or of `CLOCK_MONOTONIC` from Linux's `clock_gettime()`.
   *
   * {{{
   *   timer.clockMonotonic(NANOSECONDS)
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
   * As a matter of implementation detail, the JVM will use
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
   * The recommendation is to use this monotonic clock when doing
   * measurements of execution time, or if you value monotonically
   * increasing values more than a correspondence to wall-time, or
   * otherwise prefer [[clockRealTime]].
   */
  def clockMonotonic(unit: TimeUnit): F[Long]

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

  /**
   * Asynchronous boundary described as an effectful `F[_]` that
   * can be used in `flatMap` chains to "shift" the continuation
   * of the run-loop to another thread or call stack.
   *
   * This is the [[Async.shift]] operation, without the need for an
   * `ExecutionContext` taken as a parameter.
   *
   * This `shift` operation can usually be derived from `sleep`:
   *
   * {{{
   *   timer.shift <-> timer.sleep(Duration.Zero)
   * }}}
   */
  def shift: F[Unit]
}

object Timer {
  /**
   * For a given `F` data type fetches the implicit [[Timer]]
   * instance available implicitly in the local scope.
   */
  def apply[F[_]](implicit timer: Timer[F]): Timer[F] = timer

  /**
   * Derives a [[Timer]] for any type that has a [[LiftIO]] instance,
   * from the implicitly available `Timer[IO]` that should be in scope.
   */
  def derive[F[_]](implicit F: LiftIO[F], timer: Timer[IO]): Timer[F] =
    new Timer[F] {
      def shift: F[Unit] =
        F.liftIO(timer.shift)
      def sleep(timespan: FiniteDuration): F[Unit] =
        F.liftIO(timer.sleep(timespan))
      def clockRealTime(unit: TimeUnit): F[Long] =
        F.liftIO(timer.clockRealTime(unit))
      def clockMonotonic(unit: TimeUnit): F[Long] =
        F.liftIO(timer.clockMonotonic(unit))
    }
}
