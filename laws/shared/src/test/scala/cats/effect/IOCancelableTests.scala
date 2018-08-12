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

import java.util.concurrent.atomic.AtomicInteger

import cats.effect.internals.{Callback, CancelUtils, Conversions, IOPlatform}
import cats.effect.laws.discipline.arbitrary._
import cats.effect.util.CompositeException
import cats.implicits._
import cats.laws._
import cats.laws.discipline._
import org.scalacheck.Prop

import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class IOCancelableTests extends BaseTestsSuite {
  testAsync("IO.cancelBoundary <-> IO.unit") { implicit ec =>
    val f = IO.cancelBoundary.unsafeToFuture()
    f.value shouldBe Some(Success(()))
  }

  testAsync("IO.cancelBoundary can be canceled") { implicit ec =>
    val f = (IO.shift *> IO.cancelBoundary).unsafeToFuture()
    f.value shouldBe None
    ec.tick()
    f.value shouldBe Some(Success(()))
  }

  testAsync("fa *> IO.cancelBoundary <-> fa") { implicit ec =>
    Prop.forAll { (fa: IO[Int]) =>
      fa <* IO.cancelBoundary <-> fa
    }
  }

  testAsync("(fa *> IO.cancelBoundary).cancel <-> IO.never") { implicit ec =>
    Prop.forAll { (fa: IO[Int]) =>
      val received =
        for {
          f <- (fa <* IO.cancelBoundary).start
          _ <- f.cancel
          a <- f.join
        } yield a

      received <-> IO.never
    }
  }

  testAsync("fa.onCancelRaiseError(e) <-> fa") { implicit ec =>
    Prop.forAll { (fa: IO[Int], e: Throwable) =>
      fa.onCancelRaiseError(e) <-> fa
    }
  }

  testAsync("(fa *> IO.cancelBoundary).onCancelRaiseError(e).cancel <-> IO.raiseError(e)") { implicit ec =>
    Prop.forAll { (fa: IO[Int], e: Throwable) =>
      val received =
        for {
          f <- (fa <* IO.cancelBoundary).onCancelRaiseError(e).start
          _ <- f.cancel
          a <- f.join
        } yield a

      received <-> IO.raiseError(e)
    }
  }

  testAsync("uncancelable") { implicit ec =>
    Prop.forAll { (fa: IO[Int]) =>
      val received =
        for {
          f <- (fa <* IO.cancelBoundary).uncancelable.start
          _ <- f.cancel
          a <- f.join
        } yield a

      received <-> fa
    }
  }

  testAsync("task.start.flatMap(id) <-> task") { implicit sc =>
    Prop.forAll { (task: IO[Int]) =>
      task.start.flatMap(_.join) <-> task
    }
  }

  testAsync("task.start is cancelable") { implicit sc =>
    val task = (IO.shift *> IO.cancelBoundary *> IO(1)).start.flatMap(_.join)

    val p = Promise[Int]()
    val cancel = task.unsafeRunCancelable(Callback.promise(p))
    sc.state.tasks.isEmpty shouldBe false

    cancel.unsafeRunSync()
    sc.tick()
    sc.state.tasks.isEmpty shouldBe true
    p.future.value shouldBe None
  }

  testAsync("onCancelRaiseError back-pressures on the finalizer") { ec =>
    implicit val timer = ec.timer[IO]

    val p1 = Promise[Unit]()
    val io = IO.cancelable[Unit] { _ =>
      IO.sleep(3.seconds) *> IO(p1.success(()))
    }

    val dummy = new RuntimeException("dummy")
    val p2 = Promise[Unit]()
    val token = io.onCancelRaiseError(dummy).unsafeRunCancelable(r => p2.complete(Conversions.toTry(r)))

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
    p2.future.value shouldBe Some(Failure(dummy))
  }

  testAsync("onCancelRaiseError deals with the error of the finalizer") { ec =>
    val dummy1 = new RuntimeException("dummy1")
    val dummy2 = new RuntimeException("dummy2")

    val io = IO.cancelable[Unit](_ => IO.raiseError(dummy2)).onCancelRaiseError(dummy1)
    val p = Promise[Unit]()
    val cancel = io.unsafeRunCancelable(r => p.complete(Conversions.toTry(r))).unsafeToFuture()

    ec.tick()
    cancel.value shouldBe Some(Failure(dummy2))
    p.future.value shouldBe Some(Failure(dummy1))
  }

  testAsync("bracket back-pressures on the finalizer") { ec =>
    implicit val timer = ec.timer[IO]

    val p1 = Promise[Unit]()
    val io = IO.unit.bracket(_ => IO.never: IO[Unit]) { _ =>
      IO.sleep(3.seconds) *> IO(p1.success(()))
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
    implicit val timer = ec.timer[IO]

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
    implicit val timer = ec.timer[IO]
    val atom = new AtomicInteger(0)

    val io =
      IO(1).bracket { _ =>
        IO(2).bracket { _ =>
          IO(3).bracket(_ => IO.never: IO[Unit]) { x3 =>
            IO.sleep(3.seconds) *> IO {
              atom.compareAndSet(0, x3) shouldBe true
            }
          }
        } { x2 =>
          IO.sleep(2.second) *> IO {
            atom.compareAndSet(3, x2) shouldBe true
          }
        }
      } { x1 =>
        IO.sleep(1.second) *> IO {
          atom.compareAndSet(2, x1) shouldBe true
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

  testAsync("errors of nested brackets get aggregated") { _ =>
    val dummy1 = new RuntimeException("dummy1")
    val dummy2 = new RuntimeException("dummy2")
    val dummy3 = new RuntimeException("dummy3")

    val io =
      IO(1).bracket { _ =>
        IO(2).bracket { _ =>
          IO(3).bracket(_ => IO.never: IO[Unit]) { _ => IO.raiseError(dummy3) }
        } { _ =>
          IO.raiseError(dummy2)
        }
      } { _ =>
        IO.raiseError(dummy1)
      }

    val p = Promise[Unit]()
    val token = io.unsafeRunCancelable(r => p.complete(Conversions.toTry(r)))
    val f = token.unsafeToFuture()

    p.future.value shouldBe None

    if (IOPlatform.isJVM) {
      f.value shouldBe Some(Failure(dummy3))
      dummy3.getSuppressed.toList shouldBe List(dummy2, dummy1)
    } else {
      f.value shouldBe Some(Failure(CompositeException(dummy3, dummy2, List(dummy1))))
    }
  }
}