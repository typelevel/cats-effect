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

package cats
package effect
package concurrent

import cats.implicits._
import cats.effect.kernel.{ConcurrentBracket, Outcome, Temporal, TemporalBracket}, Outcome._
import cats.effect.testkit.pure._
import cats.effect.testkit.TimeT

import org.specs2.mutable.Specification

import scala.concurrent.duration._
// import scala.concurrent.TimeoutException

//TODO uncomment these tests once the Temporal instance for PureConc supports them
class TemporalSpec extends Specification { outer =>

  type F[A] = PureConc[Throwable, A]

  implicit val B: ConcurrentBracket[F, Throwable] = concurrentBForPureConc[Throwable]
  implicit val F: TemporalBracket[TimeT[F, *], Throwable] =
    TimeT.temporalB[F, Throwable]

  val loop: TimeT[F, Unit] = F.sleep(5.millis).foreverM

  //TODO enable these tests once Temporal for TimeT is fixed
  "temporal" should {
    "timeout" should {
      "succeed" in {
        val op = Temporal.timeout(F.pure(true), 100.millis)

        run(TimeT.run(op)) mustEqual Completed(Some(true))
      }

      // "cancel a loop" in {
      //   val op: TimeT[F, Either[Throwable, Unit]] = Temporal.timeout(loop, 5.millis).attempt

      //   run(TimeT.run(op)) must beLike {
      //     case Completed(Some(Left(e))) => e must haveClass[TimeoutException]
      //   }
      // }
    }

    // "timeoutTo" should {
    //   "succeed" in {
    //     val op: TimeT[F, Boolean] = Temporal.timeoutTo(F.pure(true), 5.millis, F.raiseError(new RuntimeException))

    //     run(TimeT.run(op)) mustEqual Completed(Some(true))
    //   }

    //   "use fallback" in {
    //     val op: TimeT[F, Boolean] = Temporal.timeoutTo(loop >> F.pure(false), 5.millis, F.pure(true))

    //     run(TimeT.run(op)) mustEqual Completed(Some(true))
    //   }
    // }
  }

}
