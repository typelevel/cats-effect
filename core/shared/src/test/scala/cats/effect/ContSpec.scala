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

import org.specs2.execute._

import cats.syntax.all._
import scala.concurrent.duration._

class ContSpec extends BaseSpec { outer =>
  def execute(io: IO[_], times: Int, i: Int = 0): IO[Success] = {
    if (i == times) IO(success)
    else io >> execute(io, times, i + 1)
  }

  // TODO move these to IOSpec. Generally review our use of `ticked` in IOSpec
  "get resumes" in real {
    val io = IO.cont {
      new Cont[IO, Int] {
        def apply[F[_]](resume: Either[Throwable,Int] => Unit, get: F[Int], lift: IO ~> F)(implicit Cancel: MonadCancel[F,Throwable]): F[Int] =
          lift(IO(resume(Right(42)))) >> get
      }
    }

    val test = io.flatMap(r => IO(r mustEqual 42))

    execute(test, 100000)
  }

  "callback resumes" in real {
   val (scheduler, close) = unsafe.IORuntime.createDefaultScheduler()

    val io = IO.cont {
      new Cont[IO, Int] {
        def apply[F[_]](resume: Either[Throwable,Int] => Unit, get: F[Int], lift: IO ~> F)(implicit Cancel: MonadCancel[F,Throwable]): F[Int] =
           lift(IO(scheduler.sleep(10.millis, () => resume(Right(42))))) >> get

      }
    }

    val test = io.flatMap(r => IO(r mustEqual 42))

    execute(test, 100).guarantee(IO(close()))
  }

  "cont.get can be canceled" in real {

    def never = IO.cont {
      new Cont[IO, Int] {
        def apply[F[_]](resume: Either[Throwable,Int] => Unit, get: F[Int], lift: IO ~> F)(implicit Cancel: MonadCancel[F,Throwable]): F[Int] =
          get

      }
    }

    val io = never.start.flatMap(_.cancel)

    execute(io, 100000)
  }

  "cancelation corner case: Cont.get running finalisers " in real {
    import kernel._

    def wait(syncLatch: Ref[IO, Boolean]): IO[Unit] =
      syncLatch.get.flatMap { switched =>
        (IO.cede >> wait(syncLatch)).whenA(!switched)
      }

    val io = {
      for {
        d <- Deferred[IO, Unit]
        latch <- Ref[IO].of(false)
        fb <- (latch.set(true) *> d.get).start
        _ <- wait(latch)
        _ <- d.complete(())
        _ <- fb.cancel
      } yield ()
    }

    execute(io, 100000)
  }
}
