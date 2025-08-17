/*
 * Copyright 2020-2025 Typelevel
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

import scala.concurrent.duration._

class CyclicBarrierSuite extends BaseSuite {

  cyclicBarrierTests("Cyclic barrier", CyclicBarrier.apply)
  cyclicBarrierTests(
    "Cyclic barrier mapK",
    CyclicBarrier.apply[IO](_).map(_.mapK(FunctionK.id)))

  private def cyclicBarrierTests(name: String, newBarrier: Int => IO[CyclicBarrier[IO]]) = {
    real(s"$name  - should raise an exception when constructed with a negative capacity") {
      IO.defer(newBarrier(-1)).mustFailWith[IllegalArgumentException]
    }

    real(s"$name  - should raise an exception when constructed with zero capacity") {
      IO.defer(newBarrier(0)).mustFailWith[IllegalArgumentException]
    }

    ticked(s"$name  - await is blocking") { implicit ticker =>
      assertNonTerminate(newBarrier(2).flatMap(_.await))
    }

    ticked(s"$name  - await is cancelable") { implicit ticker =>
      assertCompleteAs(newBarrier(2).flatMap(_.await).timeoutTo(1.second, IO.unit), ())
    }

    real(s"$name  - await releases all fibers") {
      newBarrier(2).flatMap { barrier =>
        (barrier.await, barrier.await).parTupled.void.mustEqual(())
      }
    }

    ticked(s"$name  - should reset once full") { implicit ticker =>
      assertNonTerminate(newBarrier(2).flatMap { barrier =>
        (barrier.await, barrier.await).parTupled >>
          barrier.await
      })
    }

    ticked(s"$name  - should clean up upon cancelation of await") { implicit ticker =>
      assertNonTerminate(newBarrier(2).flatMap { barrier =>
        // This will time out, so count goes back to 2
        barrier.await.timeoutTo(1.second, IO.unit) >>
          // Therefore count goes only down to 1 when this awaits, and will block again
          barrier.await
      })
    }

    real(s"$name  - barrier of capacity 1 is a no op") {
      newBarrier(1).flatMap(_.await).mustEqual(())
    }

    /*
     * Original implementation in b31d5a486757f7793851814ec30e056b9c6e40b8
     * had a race between cancelation of an awaiting fiber and
     * resetting the barrier once it's full
     */
    real(s"$name  - race fiber cancel and barrier full") {
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
