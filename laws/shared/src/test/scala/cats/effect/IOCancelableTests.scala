/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.internals.{Callback, CancelUtils, Conversions}
import cats.effect.laws.discipline.arbitrary._
import cats.implicits._
import cats.laws._
import cats.laws.discipline._

import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class IOCancelableTests extends BaseTestsSuite {
  testAsync("IO.cancelBoundary <-> IO.unit") { _ =>
    val f = IO.cancelBoundary.unsafeToFuture()
    f.value shouldBe Some(Success(()))
  }

  testAsync("IO.cancelBoundary can be canceled") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val f = (IO.shift *> IO.cancelBoundary).unsafeToFuture()
    f.value shouldBe None
    ec.tick()
    f.value shouldBe Some(Success(()))
  }

  testAsync("fa <* IO.cancelBoundary <-> fa") { implicit ec =>
    check { (fa: IO[Int]) =>
      fa <* IO.cancelBoundary <-> fa
    }
  }

  testAsync("(fa <* IO.cancelBoundary).cancel <-> IO.never") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    check { (fa: IO[Int]) =>
      val received =
        for {
          f <- (fa <* IO.cancelBoundary).start
          _ <- f.cancel
          a <- f.join
        } yield a

      received <-> IO.never
    }
  }

  testAsync("task.start.flatMap(id) <-> task") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    check { (task: IO[Int]) =>
      task.start.flatMap(_.join) <-> task
    }
  }

  testAsync("task.start is cancelable") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val task = (IO.shift *> IO.cancelBoundary *> IO(1)).start.flatMap(_.join)

    val p = Promise[Int]()
    val cancel = task.unsafeRunCancelable(Callback.promise(p))
    ec.state.tasks.isEmpty shouldBe false

    cancel.unsafeRunSync()
    ec.tick()
    ec.state.tasks.isEmpty shouldBe true
    p.future.value shouldBe None
  }

  testAsync("bracket back-pressures on the finalizer") { ec =>
    implicit val timer: Timer[IO] = ec.timer[IO]

    val p1 = Promise[Unit]()
    val io = IO.unit.bracket(_ => IO.never: IO[Unit]) { _ =>
      IO.sleep(3.seconds) *> IO(p1.success(())).void
    }

    val p2 = Promise[Unit]()
    val token = io.unsafeRunCancelable(r => p2.complete(Conversions.toTry(r)))

    ec.tick()
    p1.future.value shouldBe None
    p2.future.value shouldBe None

    val cancel = token.unsafeToFuture()
    ec.tick(2.seconds)
    cancel.value shouldBe None
    p1.future.value shouldBe None
    p2.future.value shouldBe None

    ec.tick(1.second)
    cancel.value shouldBe Some(Success(()))
    p1.future.value shouldBe Some(Success(()))
    p2.future.value shouldBe None
  }

  testAsync("CancelUtils.cancelAll") { ec =>
    implicit val timer: Timer[IO] = ec.timer[IO]

    val token1 = IO.sleep(1.seconds)
    val token2 = IO.sleep(2.seconds)
    val token3 = IO.sleep(3.seconds)

    val f1 = CancelUtils.cancelAll(token1, token2, token3).unsafeToFuture()

    ec.tick()
    f1.value shouldBe None
    ec.tick(3.seconds)
    f1.value shouldBe None
    ec.tick(3.seconds)
    f1.value shouldBe Some(Success(()))

    val f2 = CancelUtils.cancelAll(token3, token2, token1).unsafeToFuture()

    ec.tick()
    f2.value shouldBe None
    ec.tick(3.seconds)
    f2.value shouldBe None
    ec.tick(3.seconds)
    f2.value shouldBe Some(Success(()))
  }

  testAsync("nested brackets are sequenced") { ec =>
    implicit val timer: Timer[IO] = ec.timer[IO]
    val atom = new AtomicInteger(0)

    val io =
      IO(1).bracket { _ =>
        IO(2).bracket { _ =>
          IO(3).bracket(_ => IO.never: IO[Unit]) { x3 =>
            IO.sleep(3.seconds) *> IO {
              atom.compareAndSet(0, x3) shouldBe true
              ()
            }
          }
        } { x2 =>
          IO.sleep(2.seconds) *> IO {
            atom.compareAndSet(3, x2) shouldBe true
            ()
          }
        }
      } { x1 =>
        IO.sleep(1.second) *> IO {
          atom.compareAndSet(2, x1) shouldBe true
          ()
        }
      }

    val p = Promise[Unit]()
    val token = io.unsafeRunCancelable(r => p.complete(Conversions.toTry(r)))
    val f = token.unsafeToFuture()

    ec.tick()
    f.value shouldBe None
    atom.get() shouldBe 0

    ec.tick(1.second)
    atom.get() shouldBe 0

    ec.tick(2.seconds)
    f.value shouldBe None
    atom.get() shouldBe 3

    ec.tick(2.seconds)
    f.value shouldBe None
    atom.get() shouldBe 2

    ec.tick(1.seconds)
    f.value shouldBe Some(Success(()))
    atom.get() shouldBe 1
  }

  testAsync("first error in nested brackets gets returned, the rest are logged") { _ =>
    val dummy1 = new RuntimeException("dummy1")
    val dummy2 = new RuntimeException("dummy2")
    val dummy3 = new RuntimeException("dummy3")

    val io =
      IO(1).bracket { _ =>
        IO(2).bracket { _ =>
          IO(3).bracket(_ => IO.never: IO[Unit]) { _ =>
            IO.raiseError(dummy3)
          }
        } { _ =>
          IO.raiseError(dummy2)
        }
      } { _ =>
        IO.raiseError(dummy1)
      }

    val p = Promise[Unit]()
    val sysErr = new ByteArrayOutputStream()

    val f = catchSystemErrInto(sysErr) {
      val token = io.unsafeRunCancelable(r => p.complete(Conversions.toTry(r)))
      token.unsafeToFuture()
    }

    p.future.value shouldBe None

    f.value shouldBe Some(Failure(dummy3))
    sysErr.toString("utf-8") should include("dummy2")
    sysErr.toString("utf-8") should include("dummy1")
    dummy1.getSuppressed shouldBe empty // ensure memory isn't leaked with addSuppressed
    dummy2.getSuppressed shouldBe empty // ensure memory isn't leaked with addSuppressed
    dummy3.getSuppressed shouldBe empty // ensure memory isn't leaked with addSuppressed
  }

  // regression test for https://github.com/typelevel/cats-effect/issues/487
  testAsync("bracket can be canceled while failing to acquire") { ec =>
    implicit val timer: Timer[IO] = ec.timer[IO]

    val io = (IO.sleep(2.second) *> IO.raiseError[Unit](new Exception()))
      .bracket(_ => IO.unit)(_ => IO.unit)

    val cancelToken = io.unsafeRunCancelable(_ => ())

    ec.tick(1.second)
    val cancellation = cancelToken.unsafeToFuture()

    ec.tick(1.second)
    cancellation.value shouldBe Some(Success(()))
  }
}
