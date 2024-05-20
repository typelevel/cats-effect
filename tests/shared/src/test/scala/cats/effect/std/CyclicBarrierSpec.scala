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

package cats.effect
package std

import cats.arrow.FunctionK
import cats.implicits._

import org.specs2.specification.core.Fragments

import scala.concurrent.duration._

class CyclicBarrierSpec extends BaseSpec {

  "Cyclic barrier" should {
    cyclicBarrierTests("Cyclic barrier", CyclicBarrier.apply)
    cyclicBarrierTests(
      "Cyclic barrier mapK",
      CyclicBarrier.apply[IO](_).map(_.mapK(FunctionK.id)))
  }

  private def cyclicBarrierTests(
      name: String,
      newBarrier: Int => IO[CyclicBarrier[IO]]): Fragments = {
    s"$name - should raise an exception when constructed with a negative capacity" in real {
      IO.defer(newBarrier(-1)).mustFailWith[IllegalArgumentException]
    }

    s"$name - should raise an exception when constructed with zero capacity" in real {
      IO.defer(newBarrier(0)).mustFailWith[IllegalArgumentException]
    }

    s"$name - await is blocking" in ticked { implicit ticker =>
      newBarrier(2).flatMap(_.await) must nonTerminate
    }

    s"$name - await is cancelable" in ticked { implicit ticker =>
      newBarrier(2).flatMap(_.await).timeoutTo(1.second, IO.unit) must completeAs(())
    }

    s"$name - await releases all fibers" in real {
      newBarrier(2).flatMap { barrier =>
        (barrier.await, barrier.await).parTupled.void.mustEqual(())
      }
    }

    s"$name - should reset once full" in ticked { implicit ticker =>
      newBarrier(2).flatMap { barrier =>
        (barrier.await, barrier.await).parTupled >>
          barrier.await
      } must nonTerminate
    }

    s"$name - should clean up upon cancelation of await" in ticked { implicit ticker =>
      newBarrier(2).flatMap { barrier =>
        // This will time out, so count goes back to 2
        barrier.await.timeoutTo(1.second, IO.unit) >>
          // Therefore count goes only down to 1 when this awaits, and will block again
          barrier.await
      } must nonTerminate
    }

    s"$name - barrier of capacity 1 is a no op" in real {
      newBarrier(1).flatMap(_.await).mustEqual(())
    }

    /*
     * Original implementation in b31d5a486757f7793851814ec30e056b9c6e40b8
     * had a race between cancelation of an awaiting fiber and
     * resetting the barrier once it's full
     */
    s"$name - race fiber cancel and barrier full" in real {
      val iterations = 100

      val run = newBarrier(2)
        .flatMap { barrier =>
          barrier.await.start.flatMap { fiber =>
            barrier.await.race(fiber.cancel).flatMap {
              case Left(_) =>
                // without the epoch check in CyclicBarrier,
                // a late cancelation would increment the count
                // after the barrier has already reset,
                // causing this code to never terminate (test times out)
                (barrier.await, barrier.await).parTupled.void
              case Right(_) => IO.unit
            }
          }
        }
        .mustEqual(())

      List.fill(iterations)(run).reduce(_ >> _)
    }
  }
}
