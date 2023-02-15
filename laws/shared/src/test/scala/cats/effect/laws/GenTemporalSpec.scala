/*
 * Copyright 2020-2023 Typelevel
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

import cats.effect.kernel.Temporal
import cats.effect.kernel.testkit.TimeT
import cats.effect.kernel.testkit.pure._
import cats.syntax.all._

import org.specs2.mutable.Specification

import scala.concurrent.duration._
// import scala.concurrent.TimeoutException

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
    }

    "timeoutTo" should {
      "return identity when infinite duration" in {
        val fa: TimeT[F, Boolean] = F.pure(true)
        val fallback: TimeT[F, Boolean] = F.raiseError(new RuntimeException)
        F.timeoutTo(fa, Duration.Inf, fallback) mustEqual fa
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
    "timeout" should {
      "succeed" in {
        val op = F.timeout(F.pure(true), 10.seconds)

        run(TimeT.run(op)) mustEqual Succeeded(Some(true))
      }.pendingUntilFixed

      "cancel a loop" in {
        val op: TimeT[F, Either[Throwable, Unit]] = F.timeout(loop, 5.millis).attempt

        run(TimeT.run(op)) must beLike {
          case Succeeded(Some(Left(e))) => e must haveClass[TimeoutException]
        }
      }.pendingUntilFixed
    }

    "timeoutTo" should {
      "succeed" in {
        val op: TimeT[F, Boolean] = F.timeoutTo(F.pure(true), 5.millis, F.raiseError(new RuntimeException))

        run(TimeT.run(op)) mustEqual Succeeded(Some(true))
      }.pendingUntilFixed

      "use fallback" in {
        val op: TimeT[F, Boolean] = F.timeoutTo(loop >> F.pure(false), 5.millis, F.pure(true))

        run(TimeT.run(op)) mustEqual Succeeded(Some(true))
      }.pendingUntilFixed
    }
  }*/

}
