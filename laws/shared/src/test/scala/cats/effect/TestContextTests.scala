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

import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

class TestContextTests extends BaseTestsSuite {
  testAsync("recursive loop") { implicit ec =>
    def loop(n: Int, sum: Long): Future[Long] =
      if (n <= 0) Future(sum)
      else {
        val f1 = Future(n - 1)
        val f2 = Future(sum + n)

        f1.flatMap { newN =>
          f2.flatMap { newSum =>
            loop(newN, newSum)
          }
        }
      }

    val n = 10000
    val f = loop(n, 0)
    assert(f.value === None)

    ec.tick()
    assert(f.value === Some(Success(n * (n + 1) / 2)))
  }

  testAsync("reportFailure") { ec =>
    val dummy = new RuntimeException("dummy")
    var effect = false

    ec.execute(new Runnable {
      def run(): Unit = {
        ec.execute(new Runnable {
          def run(): Unit =
            effect = true
        })

        throw dummy
      }
    })

    assert(effect === false)
    assert(ec.state.lastReportedFailure === None)

    ec.tick()

    assert(effect === true)
    assert(ec.state.lastReportedFailure === Some(dummy))
  }

  testAsync("tickOne") { implicit ec =>
    val f = Future(1 + 1)
    assert(f.value === None)
    ec.tickOne()
    assert(f.value === Some(Success(2)))

    var count = 0
    for (_ <- 0 until 100)
      ec.execute(new Runnable {
        def run(): Unit = count += 1
      })

    assert(count === 0)
    var executed = 0
    while (ec.tickOne()) {
      executed += 1
    }

    assert(count === 100)
    assert(executed === 100)
  }

  testAsync("IO.shift via implicit ExecutionContext") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val f = IO.shift.flatMap(_ => IO(1 + 1)).unsafeToFuture()
    assert(f.value === None)

    ec.tick()
    assert(f.value === Some(Success(2)))
  }

  testAsync("IO.shift via Timer") { ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val f = IO.shift.flatMap(_ => IO(1 + 1)).unsafeToFuture()
    assert(f.value === None)

    ec.tick()
    assert(f.value === Some(Success(2)))
  }

  testAsync("timer.clock.realTime") { ec =>
    val timer = ec.timer[IO]

    val t1 = timer.clock.realTime(MILLISECONDS).unsafeRunSync()
    assert(t1 === 0)

    ec.tick(5.seconds)
    val t2 = timer.clock.realTime(MILLISECONDS).unsafeRunSync()
    assert(t2 === 5000)

    ec.tick(10.seconds)
    val t3 = timer.clock.realTime(MILLISECONDS).unsafeRunSync()
    assert(t3 === 15000)
  }

  testAsync("timer.clock.monotonic") { ec =>
    val timer = ec.timer[IO]

    val t1 = timer.clock.monotonic(MILLISECONDS).unsafeRunSync()
    assert(t1 === 0)

    ec.tick(5.seconds)
    val t2 = timer.clock.monotonic(MILLISECONDS).unsafeRunSync()
    assert(t2 === 5000)

    ec.tick(10.seconds)
    val t3 = timer.clock.monotonic(MILLISECONDS).unsafeRunSync()
    assert(t3 === 15000)
  }

  testAsync("timer.sleep") { ec =>
    val timer = ec.timer[IO]
    val delay = timer.sleep(10.seconds).map(_ => 1)
    val f = delay.unsafeToFuture()

    ec.tick()
    assert(f.value === None)

    ec.tick(1.second)
    assert(f.value === None)
    ec.tick(8.second)
    assert(f.value === None)
    ec.tick(1.second)
    assert(f.value === Some(Success(1)))
  }

  testAsync("timer.sleep is cancelable") { ec =>
    def callback[A](p: Promise[A]): (Either[Throwable, A] => Unit) = r => {
      p.complete(r match {
        case Left(e)  => Failure(e)
        case Right(a) => Success(a)
      })
    }

    val timer = ec.timer[IO]
    val delay = timer.sleep(10.seconds).map(_ => 1)

    val p1 = Promise[Int]()
    val p2 = Promise[Int]()
    val p3 = Promise[Int]()

    delay.unsafeRunCancelable(callback(p1))
    val cancel = delay.unsafeRunCancelable(callback(p2))
    delay.unsafeRunCancelable(callback(p3))

    ec.tick()
    assert(p1.future.value === None)
    assert(p2.future.value === None)
    assert(p3.future.value === None)

    cancel.unsafeRunSync()
    ec.tick()
    assert(p2.future.value === None)

    ec.tick(10.seconds)
    assert(p1.future.value === Some(Success(1)))
    assert(p2.future.value === None)
    assert(p3.future.value === Some(Success(1)))

    assert(ec.state.tasks.isEmpty, "tasks.isEmpty")
  }
}
