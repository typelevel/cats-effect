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

import kernel.Ref
import unsafe.Scheduler
import cats.effect.syntax.all._
import cats.syntax.all._
import scala.concurrent.duration._

import org.specs2.execute._

class ContSpec extends ContSpecBase {
  def cont[A](body: Cont[IO, A]): IO[A] =
    IO.cont(body)
}

class DefaultContSpec extends ContSpecBase {
  def cont[A](body: Cont[IO, A]): IO[A] =
    Async.defaultCont[IO, A](body)
}

trait ContSpecBase extends BaseSpec { outer =>
  def cont[A](body: Cont[IO, A]): IO[A]

  def execute(io: IO[_], times: Int, i: Int = 0): IO[Success] = {
    if (i == times) IO(success)
    else io >> execute(io, times, i + 1)
  }

  type Cancelable[F[_]] = MonadCancel[F, Throwable]

  "get resumes" in real {
    val io = cont {
      new Cont[IO, Int] {
        def apply[F[_]: Cancelable] = { (resume, get, lift) =>
          lift(IO(resume(Right(42)))) >> get
        }
      }
    }

    val test = io.flatMap(r => IO(r mustEqual 42))

    execute(test, 100000)
  }

  "callback resumes" in real {
    val (scheduler, close) = Scheduler.createDefaultScheduler()

    val io = cont {
      new Cont[IO, Int] {
        def apply[F[_]: Cancelable] = { (resume, get, lift) =>
          lift(IO(scheduler.sleep(10.millis, () => resume(Right(42))))) >> get
        }

      }
    }

    val test = io.flatMap(r => IO(r mustEqual 42))

    execute(test, 100).guarantee(IO(close()))
  }

  "get can be canceled" in real {
    def never =
      cont {
        new Cont[IO, Int] {
          def apply[F[_]: Cancelable] =
            (_, get, _) => get
        }
      }

    val io = never.start.flatMap(_.cancel)

    execute(io, 100000)
  }

  "nondeterministic cancelation corner case: get running finalisers " in real {
    import kernel._

    def wait(syncLatch: Ref[IO, Boolean]): IO[Unit] =
      syncLatch.get.flatMap { switched => (IO.cede >> wait(syncLatch)).whenA(!switched) }

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

  "get within onCancel - 1" in real {
    val flag = Ref[IO].of(false)

    val (scheduler, close) = Scheduler.createDefaultScheduler()

    val io =
      (flag, flag)
        .tupled
        .flatMap {
          case (start, end) =>
            cont {
              new Cont[IO, Unit] {
                def apply[F[_]: Cancelable] = { (resume, get, lift) =>
                  lift(IO(scheduler.sleep(100.millis, () => resume(().asRight)))) >>
                    get.onCancel {
                      lift(start.set(true)) >> get >> lift(end.set(true))
                    }
                }
              }
            }.timeoutTo(50.millis, ().pure[IO]) >> (start.get, end.get).tupled
        }
        .guarantee(IO(close()))

    io.flatMap { r => IO(r mustEqual true -> true) }
  }

  "get within onCancel - 2" in real {
    val flag = Ref[IO].of(false)

    val (scheduler, close) = Scheduler.createDefaultScheduler()

    val io =
      (flag, flag)
        .tupled
        .flatMap {
          case (start, end) =>
            cont {
              new Cont[IO, Unit] {
                def apply[F[_]: Cancelable] = { (resume, get, lift) =>
                  lift(IO(scheduler.sleep(100.millis, () => resume(().asRight)))) >>
                    get.onCancel {
                      lift(start.set(true) >> IO.sleep(60.millis)) >> get >> lift(end.set(true))
                    }
                }
              }
            }.timeoutTo(50.millis, ().pure[IO]) >> (start.get, end.get).tupled
        }
        .guarantee(IO(close()))

    io.flatMap { r => IO(r mustEqual true -> true) }
  }

  "get is idempotent - 1" in real {
    val io = cont {
      new Cont[IO, Int] {
        def apply[F[_]: Cancelable] = { (resume, get, lift) =>
          lift(IO(resume(Right(42)))) >> get >> get
        }
      }
    }

    val test = io.flatMap(r => IO(r mustEqual 42))

    execute(test, 100000)
  }

  "get is idempotent - 2" in real {
    val (scheduler, close) = Scheduler.createDefaultScheduler()

    val io = cont {
      new Cont[IO, Int] {
        def apply[F[_]: Cancelable] = { (resume, get, lift) =>
          lift(IO(scheduler.sleep(10.millis, () => resume(Right(42))))) >> get >> get
        }

      }
    }

    val test = io.flatMap(r => IO(r mustEqual 42))

    execute(test, 100).guarantee(IO(close()))
  }
}
