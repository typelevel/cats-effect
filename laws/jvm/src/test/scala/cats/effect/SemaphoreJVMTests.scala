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

import cats.implicits._
import org.scalatest._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

class SemaphoreJVMTests extends FunSuite with Matchers {
  implicit val ec: ExecutionContext = ExecutionContext.global
  implicit val cs = IO.contextShift(ec)
  implicit val timer: Timer[IO] = IO.timer(ec)

  test("Issue typelevel/cats-effect#380") {
    val t = 5.seconds

    def p1 = {
      for {
        sem <- cats.effect.concurrent.Semaphore[IO](0)
        _ <- (sem.acquire *> IO.unit.foreverM).start
        _ <- timer.sleep(100.millis)
        _ <- sem.release
      } yield true
    }.timeoutTo(t, IO.pure(false))

    assert(p1.unsafeRunSync, s"; timed-out after $t")
  }
}
