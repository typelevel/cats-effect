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
import cats.effect.kernel.Temporal

import scala.concurrent.duration._
import scala.concurrent.TimeoutException

//TODO delete this and enable the tests in laws/TemporalSpec once
//Temporal for TimeT is fixed
class TemporalSpec extends BaseSpec { outer =>

  "temporal" should {
    "timeout" should {
      "succeed" in real {
        val op = Temporal.timeout(IO.pure(true), 100.millis)

        op.flatMap { res =>
          IO {
            res must beTrue
          }
        }
      }

      "cancel a loop" in real {
        val op = Temporal.timeout(loop, 5.millis).attempt

        op.flatMap { res =>
          IO {
            res must beLike {
              case Left(e) => e must haveClass[TimeoutException]
            }
          }
        }
      }
    }

    "timeoutTo" should {
      "succeed" in real {
        val op =
          Temporal.timeoutTo(IO.pure(true), 5.millis, IO.raiseError(new RuntimeException))

        op.flatMap { res =>
          IO {
            res must beTrue
          }
        }
      }

      "use fallback" in real {
        val op = Temporal.timeoutTo(loop, 5.millis, IO.pure(true))

        op.flatMap { res =>
          IO {
            res must beTrue
          }
        }
      }
    }
  }

  val loop = IO.cede.foreverM
}
