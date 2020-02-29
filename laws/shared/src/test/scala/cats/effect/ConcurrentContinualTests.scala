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

import cats.effect.concurrent.Ref
import cats.implicits._
import cats.effect.implicits._
import scala.util.Success
import cats.effect.concurrent.Deferred
import org.scalatest.funsuite.AsyncFunSuite

class ContinualHangingTest extends AsyncFunSuite {
  test("Concurrent.continual can be canceled immediately after starting") {
    implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

    val task =
      Deferred[IO, Unit]
        .flatMap { started =>
          (started.complete(()) *> IO.never: IO[Unit])
            .continual(_ => IO.unit)
            .start
            .flatMap(started.get *> _.cancel)
        }
        .replicateA(10000)
        .as(true)

    task.unsafeToFuture.map(result => assert(result == true))
  }
}

class ConcurrentContinualTests extends BaseTestsSuite {
  testAsync("Concurrent.continual allows interruption of its input") { implicit ec =>
    import scala.concurrent.duration._
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    implicit val timer: Timer[IO] = ec.timer[IO]

    val task = Ref[IO].of(false).flatMap { canceled =>
      (IO.never: IO[Unit])
        .onCancel(canceled.set(true))
        .continual(_ => IO.unit)
        .timeout(1.second)
        .attempt >> canceled.get
    }

    val f = task.unsafeToFuture()
    ec.tick(1.second)
    f.value shouldBe Some(Success(true))
  }

  testAsync("Concurrent.continual backpressures on finalizers") { implicit ec =>
    import scala.concurrent.duration._
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    implicit val timer: Timer[IO] = ec.timer[IO]

    val task = Ref[IO].of(false).flatMap { canceled =>
      (IO.never: IO[Unit])
        .onCancel(IO.sleep(1.second) >> canceled.set(true))
        .continual(_ => IO.unit)
        .timeout(1.second)
        .attempt >> canceled.get
    }

    val f = task.unsafeToFuture()

    ec.tick(1.second)
    f.value shouldBe None

    ec.tick(1.second)
    f.value shouldBe Some(Success(true))
  }

  testAsync("Concurrent.continual behaves like flatMap on the happy path") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    val task = IO(1).continual(n => IO(n.toOption.get + 1)).map(_ + 1)

    val f = task.unsafeToFuture()
    ec.tick()
    f.value shouldBe Some(Success(3))
  }

  testAsync("Concurrent.continual behaves like handleErrorWith on errors") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val ex = new Exception("boom")
    val task = IO.raiseError[Unit](ex).continual(n => IO(n.swap.toOption.get))

    val f = task.unsafeToFuture()
    ec.tick()
    f.value shouldBe Some(Success(ex))
  }

  testAsync("Concurrent.continual propagates errors in the continuation") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val ex = new Exception("boom")
    val task = IO(1).continual(_ => IO.raiseError(ex)).handleError(identity)

    val f = task.unsafeToFuture()
    ec.tick()
    f.value shouldBe Some(Success(ex))
  }

  testAsync("Concurrent.continual respects the continual guarantee") { implicit ec =>
    import scala.concurrent.duration._
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    implicit val timer: Timer[IO] = ec.timer[IO]

    val task = Ref[IO].of(false).flatMap { completed =>
      IO.unit
        .continual(_ => (IO.sleep(2.second) >> completed.set(true)))
        .timeout(1.second)
        .attempt >> completed.get
    }

    val f = task.unsafeToFuture()

    ec.tick(1.second)
    f.value shouldBe None

    ec.tick(1.second)
    f.value shouldBe Some(Success(true))
  }
}
