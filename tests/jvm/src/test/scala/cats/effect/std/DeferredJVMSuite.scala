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

package cats.effect
package std

import cats.effect.kernel.Deferred
import cats.effect.unsafe.{IORuntime, IORuntimeConfig, Scheduler}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.util.concurrent.{ExecutorService, Executors, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicLong

import munit.FunSuite

class DeferredJVMParallelism1Tests extends BaseDeferredJVMTests(1)
class DeferredJVMParallelism2Tests extends BaseDeferredJVMTests(2)
class DeferredJVMParallelism4Tests extends BaseDeferredJVMTests(4)

abstract class BaseDeferredJVMTests(parallelism: Int) extends FunSuite {
  var service: ExecutorService = _

  implicit val context: ExecutionContext = new ExecutionContext {
    def execute(runnable: Runnable): Unit =
      service.execute(runnable)
    def reportFailure(cause: Throwable): Unit =
      cause.printStackTrace()
  }

  implicit val runtime: IORuntime = IORuntime.global

  override def beforeEach(context: BeforeEach): Unit =
    service = Executors.newFixedThreadPool(
      parallelism,
      new ThreadFactory {
        private[this] val index = new AtomicLong(0)
        def newThread(r: Runnable): Thread = {
          val th = new Thread(r)
          th.setName(s"semaphore-tests-${index.getAndIncrement()}")
          th.setDaemon(false)
          th
        }
      }
    )

  override def afterEach(context: AfterEach): Unit = {
    service.shutdown()
    assert(service.awaitTermination(60, TimeUnit.SECONDS), "has active threads")
  }

  // ----------------------------------------------------------------------------
  val iterations = if (isCI) 1000 else 10000
  val timeout = if (isCI) 30.seconds else 10.seconds

  def cleanupOnError[A](task: IO[A], f: FiberIO[?]) =
    task guaranteeCase {
      case Outcome.Canceled() | Outcome.Errored(_) =>
        f.cancel

      case _ =>
        IO.unit
    }

  // test("Deferred — issue #380: producer keeps its thread, consumer stays forked") {
  //   for (_ <- 0 until iterations) {
  //     val name = Thread.currentThread().getName

  //     def get(df: Deferred[IO, Unit]) =
  //       for {
  //         _ <- IO(Thread.currentThread().getName must not be equalTo(name))
  //         _ <- df.get
  //         _ <- IO(Thread.currentThread().getName must not be equalTo(name))
  //       } yield ()

  //     val task = for {
  //       df <- Deferred[IO, Unit]
  //       fb <- get(df).start
  //       _ <- IO(Thread.currentThread().assertEquals(getName, name))
  //       _ <- df.complete(())
  //       _ <- IO(Thread.currentThread().assertEquals(getName, name))
  //       _ <- fb.join
  //     } yield ()

  //     task.unsafeRunTimed(timeout).assert(nonEmpty)
  //   }

  //   success
  // }

  // test("Deferred — issue #380: with foreverM") {
  //   for (_ <- 0 until iterations) {
  //     val cancelLoop = new AtomicBoolean(false)
  //     val unit = IO {
  //       if (cancelLoop.get()) throw new CancelationException
  //     }

  //     try {
  //       val task = for {
  //         df <- Deferred[IO, Unit]
  //         latch <- Deferred[IO, Unit]
  //         fb <- (latch.complete(()) *> df.get *> unit.foreverM).start
  //         _ <- latch.get
  //         _ <- cleanupOnError(df.complete(()).timeout(timeout), fb)
  //         _ <- fb.cancel
  //       } yield ()

  //       task.unsafeRunTimed(timeout).assert(nonEmpty)
  //     } finally {
  //       cancelLoop.set(true)
  //     }
  //   }

  //   success
  // }

  test("Deferred — issue #380: with cooperative light async boundaries") {
    def run = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == 512) IO.async[Unit](cb => IO(cb(Right(()))).as(None)) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)

      for {
        d <- Deferred[IO, Unit]
        latch <- Deferred[IO, Unit]
        fb <- (latch.complete(()) *> d.get *> foreverAsync(0)).start
        _ <- latch.get
        _ <- cleanupOnError(d.complete(()).timeout(timeout), fb)
        _ <- fb.cancel
      } yield true
    }

    for (_ <- 0 until iterations) {
      assert(run.unsafeRunTimed(timeout).nonEmpty)
    }
  }

  test("Deferred — issue #380: with cooperative full async boundaries") {
    def run = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == 512) IO.unit.start.flatMap(_.join) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)

      for {
        d <- Deferred[IO, Unit]
        latch <- Deferred[IO, Unit]
        fb <- (latch.complete(()) *> d.get *> foreverAsync(0)).start
        _ <- latch.get
        _ <- cleanupOnError(d.complete(()).timeout(timeout), fb)
        _ <- fb.cancel
      } yield true
    }

    for (_ <- 0 until iterations) {
      assert(run.unsafeRunTimed(timeout).nonEmpty)
    }
  }

  // TODO move this back to run on both JVM and JS once we have a better test
  // setup than unsafeRunRealistic
  test("issue #380: complete doesn't block, test #2") {
    def execute(times: Int): IO[Boolean] = {
      val task = for {
        d <- Deferred[IO, Unit]
        latch <- Deferred[IO, Unit]
        fb <- (latch.complete(()) *> d.get *> IO.unit.foreverM).start
        _ <- latch.get
        _ <- d.complete(()).timeout(15.seconds).guarantee(fb.cancel)
      } yield {
        true
      }

      task.flatMap { r =>
        if (times > 0) execute(times - 1)
        else IO.pure(r)
      }
    }

    assertEquals(unsafeRunRealistic(execute(100))(), Some(true))
  }

  def unsafeRunRealistic[A](ioa: IO[A])(
      errors: Throwable => Unit = _.printStackTrace()): Option[A] = {
    // TODO this code is now in 4 places; should be in 1
    val executor = Executors.newFixedThreadPool(
      Runtime.getRuntime().availableProcessors(),
      { (r: Runnable) =>
        val t = new Thread(() =>
          try {
            r.run()
          } catch {
            case t: Throwable =>
              t.printStackTrace()
              errors(t)
          })
        t.setDaemon(true)
        t
      }
    )

    val ctx = ExecutionContext.fromExecutor(executor)

    val scheduler = Executors.newSingleThreadScheduledExecutor { r =>
      val t = new Thread(r)
      t.setName("io-scheduler")
      t.setDaemon(true)
      t.setPriority(Thread.MAX_PRIORITY)
      t
    }

    try {
      ioa.unsafeRunTimed(10.seconds)(
        IORuntime(
          ctx,
          ctx,
          Scheduler.fromScheduledExecutor(scheduler),
          () => (),
          IORuntimeConfig()))
    } finally {
      executor.shutdown()
      scheduler.shutdown()
    }
  }
}
