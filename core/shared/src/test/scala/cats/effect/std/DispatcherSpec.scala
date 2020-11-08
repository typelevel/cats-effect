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

import scala.concurrent.duration._

class DispatcherSpec extends BaseSpec {

  "async dispatcher" should {
    "run a synchronous IO" in real {
      val ioa = IO(1).map(_ + 2)
      val rec = Dispatcher[IO].flatMap(runner =>
        Resource.liftF(IO.fromFuture(IO(runner.unsafeToFuture(ioa)))))
      rec.use(i => IO(i mustEqual 3))
    }

    "run an asynchronous IO" in real {
      val ioa = (IO(1) <* IO.cede).map(_ + 2)
      val rec = Dispatcher[IO].flatMap(runner =>
        Resource.liftF(IO.fromFuture(IO(runner.unsafeToFuture(ioa)))))
      rec.use(i => IO(i mustEqual 3))
    }

    "run several IOs back to back" in real {
      @volatile
      var counter = 0
      val increment = IO(counter += 1)

      val num = 10

      val rec = Dispatcher[IO] flatMap { runner =>
        Resource.liftF(IO.fromFuture(IO(runner.unsafeToFuture(increment))).replicateA(num).void)
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
          val rec = Dispatcher[IO] flatMap { runner =>
            Resource.liftF(subjects.parTraverse_(act => IO(runner.unsafeRunAndForget(act))))
          }

          rec.use(_ => IO.unit)
        }
      } yield ok
    }

    "forward cancelation onto the inner action" in real {
      var canceled = false

      val rec = Dispatcher[IO] flatMap { runner =>
        val run = IO {
          runner.unsafeToFutureCancelable(IO.never.onCancel(IO { canceled = true }))._2
        }

        Resource liftF {
          run.flatMap(ct => IO.sleep(500.millis) >> IO.fromFuture(IO(ct())))
        }
      }

      rec.use(_ => IO(canceled must beTrue))
    }

    "cancel all inner effects when canceled" in real {
      @volatile
      var canceledA = false
      @volatile
      var canceledB = false

      val rec = Dispatcher[IO] flatMap { runner =>
        Resource liftF {
          IO {
            // these finalizers never return, so this test is intentionally designed to hang
            // they flip their booleans first though; this is just testing that both run in parallel
            val a = IO.never.onCancel(IO { canceledA = true } *> IO.never)
            val b = IO.never.onCancel(IO { canceledB = true } *> IO.never)

            runner.unsafeRunAndForget(a)
            runner.unsafeRunAndForget(b)
          }
        }
      }

      for {
        gate <- Deferred[IO, Unit]
        _ <- (rec.use(_ => IO.unit) *> gate.complete(())).start
        _ <- gate.get

        r <- IO {
          // if we don't run the finalizers in parallel, one of these will be false
          canceledA must beTrue
          canceledB must beTrue
        }
      } yield r
    }

    "raise an error on leaked runner" in real {
      Dispatcher[IO].use(IO.pure(_)) flatMap { runner =>
        IO {
          runner.unsafeRunAndForget(IO(ko)) must throwAn[IllegalStateException]
        }
      }
    }
  }
}
