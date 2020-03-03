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

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

import org.scalatest._
import org.scalatest.matchers.should.Matchers
import org.scalatest.funsuite.AnyFunSuite
import cats.syntax.all._

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class IOJVMTests extends AnyFunSuite with Matchers {
  val ThreadName = "test-thread"

  val TestEC = new ExecutionContext {
    def execute(r: Runnable): Unit = {
      val th = new Thread(r)
      th.setName(ThreadName)
      th.start()
    }

    def reportFailure(cause: Throwable): Unit =
      throw cause
  }

  test("shift contiguous prefix and suffix, but not interfix") {
    val name: IO[String] = IO(Thread.currentThread().getName)

    val aname: IO[String] = IO.async { cb =>
      new Thread {
        start()
        override def run() =
          cb(Right(Thread.currentThread().getName))
      }
      ()
    }

    val test = for {
      _ <- IO.shift(TestEC)
      n1 <- name
      n2 <- name
      n3 <- aname
      n4 <- name
      _ <- IO.shift(TestEC)
      n5 <- name
      n6 <- name
    } yield (n1, n2, n3, n4, n5, n6)

    val (n1, n2, n3, n4, n5, n6) = test.unsafeRunSync()

    n1 shouldEqual ThreadName
    n2 shouldEqual ThreadName
    (n3 should not).equal(ThreadName)
    (n4 should not).equal(ThreadName)
    n5 shouldEqual ThreadName
    n6 shouldEqual ThreadName
  }

  test("unsafeRunTimed(Duration.Undefined) throws exception") {
    val never = IO.async[Int](_ => ())

    intercept[IllegalArgumentException] {
      never.unsafeRunTimed(Duration.Undefined)
    }
  }

  test("unsafeRunTimed times-out on unending IO") {
    val never = IO.async[Int](_ => ())
    val start = System.currentTimeMillis()
    val received = never.unsafeRunTimed(100.millis)
    val elapsed = System.currentTimeMillis() - start

    received shouldEqual None
    assert(elapsed >= 100)
  }

  test("parMap2 concurrently") {
    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val cs: ContextShift[IO] = IO.contextShift(global)

    val io1 = IO.shift *> IO(1)
    val io2 = IO.shift *> IO(2)

    for (_ <- 0 until 1000) {
      val r = (io1, io2).parMapN(_ + _).unsafeRunSync()
      r shouldEqual 3
    }
  }

  test("long synchronous loops that are forked are cancelable") {
    val thread = new AtomicReference[Thread](null)
    implicit val ec: ExecutionContext = new ExecutionContext {
      def execute(runnable: Runnable): Unit = {
        val th = new Thread(runnable)
        if (!thread.compareAndSet(null, th))
          throw new IllegalStateException("Execute again")
        th.start()
      }
      def reportFailure(cause: Throwable): Unit =
        cause.printStackTrace()
    }

    try {
      val latch = new java.util.concurrent.CountDownLatch(1)
      def loop(): IO[Int] = IO.suspend(loop())

      implicit val ctx: ContextShift[IO] = IO.contextShift(ec)
      val task = IO.shift *> IO(latch.countDown()) *> loop()
      val c = task.unsafeRunCancelable {
        case Left(e) => e.printStackTrace()
        case _       => ()
      }

      latch.await(10, TimeUnit.SECONDS)
      // Cancelling
      c.unsafeRunSync()
      // Joining thread should succeed in case of cancelation
      val th = thread.get()
      th.join(1000 * 10) // 10 seconds

      if (th.isAlive) {
        fail("thread is still active")
      }
    } finally {
      val th = thread.get()
      if (th != null && th.isAlive)
        th.interrupt()
    }
  }

  test("fromFuture shifts continuation") {
    import scala.concurrent.ExecutionContext.Implicits.global

    implicit val CS: ContextShift[IO] = IO.contextShift(TestEC)

    val ioa = IO.fromFuture(IO(Future(()))) >> IO {
      Thread.currentThread().getName()
    }

    ioa.unsafeRunSync() shouldEqual ThreadName
  }
}
