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

package cats.effect.kernel

import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

trait Temporal[F[_], E] extends Concurrent[F, E] with Clock[F] { self: Safe[F, E] =>
  // (sleep(n) *> now) <-> now.map(_ + n + d) forSome { val d: Double }
  def sleep(time: FiniteDuration): F[Unit]

  /**
   * Returns an effect that either completes with the result of the source within
   * the specified time `duration` or otherwise evaluates the `fallback`.
   *
   * The source is cancelled in the event that it takes longer than
   * the `FiniteDuration` to complete, the evaluation of the fallback
   * happening immediately after that.
   *
   * @param duration is the time span for which we wait for the source to
   *        complete; in the event that the specified time has passed without
   *        the source completing, the `fallback` gets evaluated
   *
   * @param fallback is the task evaluated after the duration has passed and
   *        the source canceled
   */
  def timeoutTo[A](fa: F[A], duration: FiniteDuration, fallback: F[A]): F[A] =
    flatMap(race(fa, sleep(duration))) {
      case Left(a) => pure(a)
      case Right(_) => fallback
    }

  /**
   * Returns an effect that either completes with the result of the source within
   * the specified time `duration` or otherwise raises a `TimeoutException`.
   *
   * The source is cancelled in the event that it takes longer than
   * the specified time duration to complete.
   *
   * @param duration is the time span for which we wait for the source to
   *        complete; in the event that the specified time has passed without
   *        the source completing, a `TimeoutException` is raised
   */
  def timeout[A](fa: F[A], duration: FiniteDuration)(implicit ev: Throwable <:< E): F[A] = {
    val timeoutException = raiseError[A](ev(new TimeoutException(duration.toString)))
    timeoutTo(fa, duration, timeoutException)
  }

}

object Temporal {
  def apply[F[_], E](implicit F: Temporal[F, E]): F.type = F
  def apply[F[_]](implicit F: Temporal[F, _], d: DummyImplicit): F.type = F

}
