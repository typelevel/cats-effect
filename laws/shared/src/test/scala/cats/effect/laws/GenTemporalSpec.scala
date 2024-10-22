/*
 * Copyright 2020-2024 Typelevel
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

package cats
package effect
package laws

import cats.effect.kernel.{Outcome, Temporal}
import cats.effect.kernel.testkit.TimeT
import cats.effect.kernel.testkit.pure._
import cats.syntax.all._

import org.specs2.mutable.Specification

import scala.concurrent.TimeoutException
import scala.concurrent.duration._

class GenTemporalSpec extends Specification { outer =>

  type F[A] = PureConc[Throwable, A]

  implicit val F: Temporal[TimeT[F, *]] =
    TimeT.genTemporalForTimeT[F, Throwable]

  val loop: TimeT[F, Unit] = F.sleep(5.millis).foreverM

  "temporal" should {
    "timeout" should {
      "return identity when infinite duration" in {
        val fa = F.pure(true)
        F.timeout(fa, Duration.Inf) mustEqual fa
      }

      "succeed on a fast action" in {
        val fa: TimeT[F, Boolean] = F.pure(true)
        val op = F.timeout(fa, Duration.Zero)

        run(TimeT.run(op)) mustEqual Outcome.Succeeded(Some(true))
      }

      "error out on a slow action" in {
        val fa: TimeT[F, Boolean] = F.never *> F.pure(true)
        val op = F.timeout(fa, Duration.Zero)

        run(TimeT.run(op)) must beLike {
          case Outcome.Errored(e) => e must haveClass[TimeoutException]
        }
      }

      "propagate successful outcome of uncancelable action" in {
        val fa = F.uncancelable(_ => F.sleep(50.millis) *> F.pure(true))
        val op = F.timeout(fa, Duration.Zero)

        run(TimeT.run(op)) mustEqual Outcome.Succeeded(Some(true))
      }

      "propagate errors from uncancelable action" in {
        val fa = F.uncancelable { _ =>
          F.sleep(50.millis) *> F.raiseError(new RuntimeException("fa failed")) *> F.pure(true)
        }
        val op = F.timeout(fa, Duration.Zero)

        run(TimeT.run(op)) must beLike {
          case Outcome.Errored(e: RuntimeException) => e.getMessage mustEqual "fa failed"
        }
      }
    }

    "timeoutTo" should {
      "return identity when infinite duration" in {
        val fa: TimeT[F, Boolean] = F.pure(true)
        val fallback: TimeT[F, Boolean] = F.raiseError(new RuntimeException)
        F.timeoutTo(fa, Duration.Inf, fallback) mustEqual fa
      }

      "succeed on a fast action" in {
        val fa: TimeT[F, Boolean] = F.pure(true)
        val fallback: TimeT[F, Boolean] = F.raiseError(new RuntimeException)
        val op = F.timeoutTo(fa, Duration.Zero, fallback)

        run(TimeT.run(op)) mustEqual Outcome.Succeeded(Some(true))
      }

      "error out on a slow action" in {
        val fa: TimeT[F, Boolean] = F.never *> F.pure(true)
        val fallback: TimeT[F, Boolean] = F.raiseError(new RuntimeException)
        val op = F.timeoutTo(fa, Duration.Zero, fallback)

        run(TimeT.run(op)) must beLike {
          case Outcome.Errored(e) => e must haveClass[RuntimeException]
        }
      }

      "propagate successful outcome of uncancelable action" in {
        val fa = F.uncancelable(_ => F.sleep(50.millis) *> F.pure(true))
        val fallback: TimeT[F, Boolean] = F.raiseError(new RuntimeException)
        val op = F.timeoutTo(fa, Duration.Zero, fallback)

        run(TimeT.run(op)) mustEqual Outcome.Succeeded(Some(true))
      }

      "propagate errors from uncancelable action" in {
        val fa = F.uncancelable { _ =>
          F.sleep(50.millis) *> F.raiseError(new RuntimeException("fa failed")) *> F.pure(true)
        }
        val fallback: TimeT[F, Boolean] = F.raiseError(new RuntimeException)
        val op = F.timeoutTo(fa, Duration.Zero, fallback)

        run(TimeT.run(op)) must beLike {
          case Outcome.Errored(e: RuntimeException) => e.getMessage mustEqual "fa failed"
        }
      }
    }

    "timeoutAndForget" should {
      "return identity when infinite duration" in {
        val fa: TimeT[F, Boolean] = F.pure(true)
        F.timeoutAndForget(fa, Duration.Inf) mustEqual fa
      }
    }
  }

  // TODO enable these tests once Temporal for TimeT is fixed
  /*"temporal" should {
      "cancel a loop" in {
        val op: TimeT[F, Either[Throwable, Unit]] = F.timeout(loop, 5.millis).attempt

        run(TimeT.run(op)) must beLike {
          case Succeeded(Some(Left(e))) => e must haveClass[TimeoutException]
        }
      }.pendingUntilFixed
    }

    "timeoutTo" should {
      "use fallback" in {
        val op: TimeT[F, Boolean] = F.timeoutTo(loop >> F.pure(false), 5.millis, F.pure(true))

        run(TimeT.run(op)) mustEqual Succeeded(Some(true))
      }.pendingUntilFixed
    }
  }*/

}
