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

package cats
package effect

import cats.effect.syntax.all._
import cats.syntax.all._

import scala.concurrent.duration._

class ContSuite extends ContSpecBase {
  def cont[K, R](body: Cont[IO, K, R]): IO[R] =
    IO.cont(body)
}

class DefaultContSuite extends ContSpecBase {
  def cont[K, R](body: Cont[IO, K, R]): IO[R] =
    Async.defaultCont(body)
}

trait ContSpecBase extends BaseSuite with ContSpecBasePlatform { outer =>

  def cont[K, R](body: Cont[IO, K, R]): IO[R]

  def execute(io: IO[?], times: Int, i: Int = 0): IO[Unit] = {
    if (i == times) IO.unit
    else io >> execute(io, times, i + 1)
  }

  type Cancelable[F[_]] = MonadCancel[F, Throwable]

  real("get resumes") {
    val io = cont {
      new Cont[IO, Int, String] {
        def apply[F[_]: Cancelable] = { (resume, get, lift) =>
          lift(IO(resume(Right(42)))) >> get.map(_.toString)
        }
      }
    }

    val test = io.flatMap(r => IO(assertEquals(r, "42")))

    execute(test, iterations)
  }

  realWithRuntime("callback resumes") { rt =>
    val io = cont {
      new Cont[IO, Int, String] {
        def apply[F[_]: Cancelable] = { (resume, get, lift) =>
          lift(IO(rt.scheduler.sleep(10.millis, () => resume(Right(42))))) >>
            get.map(_.toString)
        }

      }
    }

    val test = io.flatMap(r => IO(assertEquals(r, "42")))

    execute(test, 100)
  }

  real("get can be canceled") {
    def never =
      cont {
        new Cont[IO, Int, String] {
          def apply[F[_]: Cancelable] =
            (_, get, _) => get.map(_.toString)
        }
      }

    val io = never.start.flatMap(_.cancel)

    execute(io, iterations)
  }

  real("nondeterministic cancelation corner case: get running finalisers ") {
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

    execute(io, iterations)
  }

  realWithRuntime("get within onCancel - 1") { rt =>
    val flag = Ref[IO].of(false)

    val io =
      (flag, flag).tupled.flatMap {
        case (start, end) =>
          cont {
            new Cont[IO, Unit, Unit] {
              def apply[F[_]: Cancelable] = { (resume, get, lift) =>
                lift(IO(rt.scheduler.sleep(2.seconds, () => resume(().asRight)))) >>
                  get.onCancel {
                    lift(start.set(true)) >> get >> lift(end.set(true))
                  }
              }
            }
          }.timeoutTo(1.second, ().pure[IO]) >> (start.get, end.get).tupled
      }

    io.flatMap { r => IO(assertEquals(r, true -> true)) }
  }

  realWithRuntime("get within onCancel - 2") { rt =>
    val flag = Ref[IO].of(false)

    val io =
      (flag, flag).tupled.flatMap {
        case (start, end) =>
          cont {
            new Cont[IO, Unit, Unit] {
              def apply[F[_]: Cancelable] = { (resume, get, lift) =>
                lift(IO(rt.scheduler.sleep(2.seconds, () => resume(().asRight)))) >>
                  get.onCancel {
                    lift(start.set(true) >> IO.sleep(60.millis)) >> get >> lift(end.set(true))
                  }
              }
            }
          }.timeoutTo(1.second, ().pure[IO]) >> (start.get, end.get).tupled
      }

    io.flatMap { r => IO(assertEquals(r, true -> true)) }
  }

  realWithRuntime("get exclusively within onCancel") { rt =>
    val test = cont {
      new Cont[IO, Unit, Unit] {
        def apply[F[_]: Cancelable] = { (resume, get, lift) =>
          lift(IO(rt.scheduler.sleep(1.second, () => resume(().asRight)))) >>
            lift(IO.never).onCancel(get)
        }
      }
    }

    test.timeoutTo(500.millis, IO.unit)
  }

  real("get is idempotent - 1") {
    val io = cont {
      new Cont[IO, Int, String] {
        def apply[F[_]: Cancelable] = { (resume, get, lift) =>
          lift(IO(resume(Right(42)))) >> get >> get.map(_.toString)
        }
      }
    }

    val test = io.flatMap(r => IO(assertEquals(r, "42")))

    execute(test, iterations)
  }

  realWithRuntime("get is idempotent - 2") { rt =>
    val io = cont {
      new Cont[IO, Int, String] {
        def apply[F[_]: Cancelable] = { (resume, get, lift) =>
          lift(IO(rt.scheduler.sleep(10.millis, () => resume(Right(42))))) >> get >>
            get.map(_.toString)
        }

      }
    }

    val test = io.flatMap(r => IO(assertEquals(r, "42")))

    execute(test, 100)
  }
}
