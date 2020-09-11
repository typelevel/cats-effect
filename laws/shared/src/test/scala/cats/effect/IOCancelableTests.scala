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
import cats.laws._
import cats.laws.discipline._
import org.scalacheck.Prop.forAll

import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class IOCancelableTests extends BaseTestsSuite {
  testAsync("IO.cancelBoundary <-> IO.unit") { _ =>
    val f = IO.cancelBoundary.unsafeToFuture()
    assertEquals(f.value, Some(Success(())))
  }

  testAsync("IO.cancelBoundary can be canceled") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val f = (IO.shift *> IO.cancelBoundary).unsafeToFuture()
    assertEquals(f.value, None)
    ec.tick()
    assertEquals(f.value, Some(Success(())))
  }

  testAsync("fa <* IO.cancelBoundary <-> fa") { implicit ec =>
    forAll { (fa: IO[Int]) =>
      fa <* IO.cancelBoundary <-> fa
    }
  }

  testAsync("(fa <* IO.cancelBoundary).cancel <-> IO.never") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    forAll { (fa: IO[Int]) =>
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

    forAll { (task: IO[Int]) =>
      task.start.flatMap(_.join) <-> task
    }
  }

  testAsync("task.start is cancelable") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val task = (IO.shift *> IO.cancelBoundary *> IO(1)).start.flatMap(_.join)

    val p = Promise[Int]()
    val cancel = task.unsafeRunCancelable(Callback.promise(p))
    assertEquals(ec.state.tasks.isEmpty, false)

    cancel.unsafeRunSync()
    ec.tick()
    assertEquals(ec.state.tasks.isEmpty, true)
    assertEquals(p.future.value, None)
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
    assertEquals(p1.future.value, None)
    assertEquals(p2.future.value, None)

    val cancel = token.unsafeToFuture()
    ec.tick(2.seconds)
    assertEquals(cancel.value, None)
    assertEquals(p1.future.value, None)
    assertEquals(p2.future.value, None)

    ec.tick(1.second)
    assertEquals(cancel.value, Some(Success(())))
    assertEquals(p1.future.value, Some(Success(())))
    assertEquals(p2.future.value, None)
  }

  testAsync("CancelUtils.cancelAll") { ec =>
    implicit val timer: Timer[IO] = ec.timer[IO]

    val token1 = IO.sleep(1.seconds)
    val token2 = IO.sleep(2.seconds)
    val token3 = IO.sleep(3.seconds)

    val f1 = CancelUtils.cancelAll(token1, token2, token3).unsafeToFuture()

    ec.tick()
    assertEquals(f1.value, None)
    ec.tick(3.seconds)
    assertEquals(f1.value, None)
    ec.tick(3.seconds)
    assertEquals(f1.value, Some(Success(())))

    val f2 = CancelUtils.cancelAll(token3, token2, token1).unsafeToFuture()

    ec.tick()
    assertEquals(f2.value, None)
    ec.tick(3.seconds)
    assertEquals(f2.value, None)
    ec.tick(3.seconds)
    assertEquals(f2.value, Some(Success(())))
  }

  testAsync("nested brackets are sequenced") { ec =>
    implicit val timer: Timer[IO] = ec.timer[IO]
    val atom = new AtomicInteger(0)

    val io =
      IO(1).bracket { _ =>
        IO(2).bracket { _ =>
          IO(3).bracket(_ => IO.never: IO[Unit]) { x3 =>
            IO.sleep(3.seconds) *> IO {
              assertEquals(atom.compareAndSet(0, x3), true)
              ()
            }
          }
        } { x2 =>
          IO.sleep(2.seconds) *> IO {
            assertEquals(atom.compareAndSet(3, x2), true)
            ()
          }
        }
      } { x1 =>
        IO.sleep(1.second) *> IO {
          assertEquals(atom.compareAndSet(2, x1), true)
          ()
        }
      }

    val p = Promise[Unit]()
    val token = io.unsafeRunCancelable(r => p.complete(Conversions.toTry(r)))
    val f = token.unsafeToFuture()

    ec.tick()
    assertEquals(f.value, None)
    assertEquals(atom.get(), 0)

    ec.tick(1.second)
    assertEquals(atom.get(), 0)

    ec.tick(2.seconds)
    assertEquals(f.value, None)
    assertEquals(atom.get(), 3)

    ec.tick(2.seconds)
    assertEquals(f.value, None)
    assertEquals(atom.get(), 2)

    ec.tick(1.seconds)
    assertEquals(f.value, Some(Success(())))
    assertEquals(atom.get(), 1)
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

    assertEquals(p.future.value, None)

    assertEquals(f.value, Some(Failure(dummy3)))
    assert(sysErr.toString("utf-8").contains("dummy2"))
    assert(sysErr.toString("utf-8").contains("dummy1"))
    assert(dummy1.getSuppressed.isEmpty) // ensure memory isn't leaked with addSuppressed
    assert(dummy2.getSuppressed.isEmpty) // ensure memory isn't leaked with addSuppressed
    assert(dummy3.getSuppressed.isEmpty) // ensure memory isn't leaked with addSuppressed
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
    assertEquals(cancellation.value, Some(Success(())))
  }
}
