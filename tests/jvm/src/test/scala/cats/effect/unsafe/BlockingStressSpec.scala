/*
 * Copyright 2020-2021 Typelevel
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
package unsafe

import cats.syntax.traverse._

import scala.concurrent.{blocking, Await, Promise}
import scala.concurrent.duration._

import java.util.concurrent.CountDownLatch

class BlockingStressSpec extends BaseSpec {

  override def executionTimeout: FiniteDuration = 10.minutes

  private val count = 171

  "Blocking" should {
    "work properly with many blocking actions and helper threads" in realWithRuntime {
      implicit rt =>
        def io(latch: CountDownLatch) = for {
          p <- IO(Promise[Unit]())
          _ <- (IO.sleep(50.millis) *> IO(p.success(()))).start
          _ <- IO(Await.result(p.future, Duration.Inf))
          _ <- IO(latch.countDown())
        } yield ()

        for {
          latch <- IO(new CountDownLatch(count))
          _ <- List.fill(count)(io(latch).start.void).sequence.void
          _ <- IO(blocking(latch.await()))
          res <- IO(ok)
        } yield res
    }
  }
}
