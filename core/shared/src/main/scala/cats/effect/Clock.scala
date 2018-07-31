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

import scala.concurrent.duration.{MILLISECONDS, NANOSECONDS, TimeUnit}

 /**
  * Clock provides access to platform's time information
  *
  * It does all of that in an `F` monadic context that can suspend
  * side effects and is capable of asynchronous execution (e.g. [[IO]]).
  *
  * This is NOT a type class, as it does not have the coherence
  * requirement.
  *
  * This is NOT a type class, as it does not have the coherence
  * requirement.
  *
  */
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
    * otherwise prefer [[clockRealTime]].
    */
  def clockMonotonic(unit: TimeUnit): F[Long]

}


object Clock  {

   /**
    * For a given `F` data type fetches the implicit [[Clock]]
    * instance available implicitly in the local scope.
    */
  def apply[F[_]](implicit clock: Clock[F]): Clock[F] = clock

   /**
    * Provides `F` instance for any `F` that has `Sync` defined
    */
  implicit def syncInstance[F[_]](implicit F: Sync[F]): Clock[F] =
    new Clock[F] {

      override def clockRealTime(unit: TimeUnit): F[Long] =
        F.delay(unit.convert(System.currentTimeMillis(), MILLISECONDS))

      override def clockMonotonic(unit: TimeUnit): F[Long] =
        F.delay(unit.convert(System.nanoTime(), NANOSECONDS))

    }

}