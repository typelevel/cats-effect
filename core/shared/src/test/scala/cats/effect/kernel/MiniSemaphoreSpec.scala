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
package kernel

import scala.concurrent.duration._
import java.util.concurrent.TimeoutException

class MiniSemaphoreSpec extends BaseSpec { outer =>

  "mini semaphore" should {

    "throw on negative n" in real {
      IO.defer(MiniSemaphore[IO](-1)).attempt.flatMap { res =>
        IO {
          res must beLike {
            case Left(e) => e must haveClass[IllegalArgumentException]
          }
        }

      }
    }

    "block if no permits available" in real {
      for {
        sem <- MiniSemaphore[IO](0)
        r <- sem.acquire.timeout(10.millis).attempt
        res <- IO {
          r must beLike {
            case Left(e) => e must haveClass[TimeoutException]
          }
        }
      } yield res
    }

    "acquire if permit is available" in real {
      for {
        sem <- MiniSemaphore[IO](1)
        r <- sem.acquire.timeout(10.millis).attempt
        res <- IO { r must beEqualTo(Right(())) }
      } yield res

    }

    "unblock when permit is released" in real {
      for {
        sem <- MiniSemaphore[IO](1)
        _ <- sem.acquire
        f <- sem.acquire.start
        _ <- IO.sleep(100.millis)
        _ <- sem.release
        r <- f.joinAndEmbedNever
        res <- IO { r must beEqualTo(()) }
      } yield res
    }

    "release permit if withPermit errors" in real {
      for {
        sem <- MiniSemaphore[IO](1)
        _ <- sem.withPermit(IO.raiseError(new RuntimeException("BOOM"))).attempt
        //withPermit should have released the permit again
        r <- sem.acquire.timeout(10.millis).attempt
        res <- IO { r must beEqualTo(Right(())) }
      } yield res
    }

    "acquire is cancelable" in real {
      for {
        sem <- MiniSemaphore[IO](1)
        _ <- sem.acquire
        f <- sem.acquire.start
        _ <- IO.sleep(100.millis)
        _ <- f.cancel
        _ <- sem.release
        //The fiber that was cancelled should not have acquired the permit
        r <- sem.acquire.timeout(10.millis).attempt
        res <- IO { r must beEqualTo(Right(())) }
      } yield res
    }
  }

}
