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

import cats.syntax.all._

import scala.concurrent.duration._

class BackpressureSpec extends BaseSpec {

  "Backpressure" should {
    "Lossy Strategy should return IO[None] when no permits are available" in ticked {
      implicit ticker =>
        val test = for {
          backpressure <- Backpressure[IO](Backpressure.Strategy.Lossy, 1)
          never = backpressure.metered(IO.never)
          lost <- IO.race(never, never)
        } yield lost.fold(identity, identity).isEmpty

        test must completeAs(true)
    }

    "Lossless Strategy should complete effects even when no permits are available" in ticked {
      implicit ticker =>
        val test = for {
          backpressure <- Backpressure[IO](Backpressure.Strategy.Lossless, 1)
          f1 <- backpressure.metered(IO.sleep(1.second) *> 1.pure[IO]).start
          f2 <- backpressure.metered(IO.sleep(1.second) *> 2.pure[IO]).start
          res1 <- f1.joinWithNever
          res2 <- f2.joinWithNever
        } yield (res1, res2)

        test must completeAs((Some(1), Some(2)))
    }
  }
}
