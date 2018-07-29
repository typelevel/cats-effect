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
 *  1. ability to delay the execution of a task with a specified time duration
 *
 * It does all of that in an `F` monadic context that can suspend
 * side effects and is capable of asynchronous execution (e.g. [[IO]]).
 *
 * This is NOT a type class, as it does not have the coherence
 * requirement.
 */
@implicitNotFound("""Cannot find an implicit value for Timer[${F}]. 
Either:
* import Timer[${F}] from your effects library
* use Timer.derive to create the necessary instance
Timer.derive requires an implicit Timer[IO], which can be available from:
* your platform (e.g. Scala JS)
* implicitly in cats.effect.IOApp
* cats.effect.IO.timer, if there's an implicit 
scala.concurrent.ExecutionContext in scope
""")
trait Timer[F[_]] extends Clock[F] {


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
      def sleep(timespan: FiniteDuration): F[Unit] =
        F.liftIO(timer.sleep(timespan))
      def clockRealTime(unit: TimeUnit): F[Long] =
        F.liftIO(timer.clockRealTime(unit))
      def clockMonotonic(unit: TimeUnit): F[Long] =
        F.liftIO(timer.clockMonotonic(unit))
    }
}
