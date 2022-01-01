/*
 * Copyright (c) 2017-2022 The Typelevel Cats-effect Project Developers
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

package cats.effect.concurrent

import cats.effect._
import cats.syntax.all._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class BackpressureTests extends CatsEffectSuite {

  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val timer: cats.effect.Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  test("Lossy Strategy should return IO[None] when no permits are available") {
    for {
      backpressure <- Backpressure[IO](Backpressure.Strategy.Lossy, 1)
      never = backpressure.metered(IO.never)
      lost <- IO.race(never, never)
    } yield assert(lost.fold(identity, identity).isEmpty)
  }

  test("Lossless Strategy should complete effects even when no permits are available") {
    for {
      backpressure <- Backpressure[IO](Backpressure.Strategy.Lossless, 1)
      f1 <- backpressure.metered(IO.sleep(1.second) *> 1.pure[IO]).start
      f2 <- backpressure.metered(IO.sleep(1.second) *> 2.pure[IO]).start
      tup <- (f1, f2).tupled.join
      (res1, res2) = tup
    } yield assertEquals((res1, res2), (Some(1), Some(2)))
  }
}
