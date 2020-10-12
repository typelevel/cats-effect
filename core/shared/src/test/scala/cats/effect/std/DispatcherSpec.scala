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

package cats.effect
package std

import cats.effect.kernel.Deferred
import cats.syntax.all._

class DispatcherSpec extends BaseSpec {

  "async dispatcher" should {
    "run a synchronous IO" in real {
      val ioa = IO(1).map(_ + 2)
      val rec = Dispatcher[IO, Int](runner => IO.fromFuture(IO(runner.unsafeToFuture(ioa))))
      rec.use(i => IO(i mustEqual 3))
    }

    "run an asynchronous IO" in real {
      val ioa = (IO(1) <* IO.cede).map(_ + 2)
      val rec = Dispatcher[IO, Int](runner => IO.fromFuture(IO(runner.unsafeToFuture(ioa))))
      rec.use(i => IO(i mustEqual 3))
    }

    "run several IOs back to back" in real {
      @volatile
      var counter = 0
      val increment = IO(counter += 1)

      val num = 10

      val rec = Dispatcher[IO, Unit] { runner =>
        IO.fromFuture(IO(runner.unsafeToFuture(increment))).replicateA(num).void
      }

      rec.use(_ => IO(counter mustEqual num))
    }

    "run multiple IOs in parallel" in real {
      val num = 10

      for {
        latches <- (0 until num).toList.traverse(_ => Deferred[IO, Unit])
        awaitAll = latches.parTraverse_(_.get)

        // engineer a deadlock: all subjects must be run in parallel or this will hang
        subjects = latches.map(latch => latch.complete(()) >> awaitAll)

        _ <- {
          val rec = Dispatcher[IO, Unit] { runner =>
            subjects.parTraverse_(act => IO(runner.unsafeRunAndForget(act)))
          }

          rec.use(_ => IO.unit)
        }
      } yield ok
    }
  }
}
