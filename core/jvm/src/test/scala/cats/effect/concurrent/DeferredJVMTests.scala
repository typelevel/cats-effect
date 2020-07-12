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

package cats.effect

import java.util.concurrent.{ExecutorService, Executors, ThreadFactory, TimeUnit}
import concurrent.Deferred
import cats.implicits._
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterEach
import scala.concurrent.duration._
import scala.concurrent.{CancellationException, ExecutionContext}
import cats.effect.IO
import cats.effect.unsafe.IORuntime

class DeferredJVMParallelism1Tests extends BaseDeferredJVMTests(1)
class DeferredJVMParallelism2Tests extends BaseDeferredJVMTests(2)
class DeferredJVMParallelism4Tests extends BaseDeferredJVMTests(4)

abstract class BaseDeferredJVMTests(parallelism: Int) extends Specification with BeforeAfterEach {
  var service: ExecutorService = _

  implicit val context: ExecutionContext = new ExecutionContext {
    def execute(runnable: Runnable): Unit =
      service.execute(runnable)
    def reportFailure(cause: Throwable): Unit =
      cause.printStackTrace()
  }

  implicit val runtime: IORuntime = IORuntime.global

  def before = {
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
  }

  def after = {
    service.shutdown()
    assert(service.awaitTermination(60, TimeUnit.SECONDS), "has active threads")
  }

  // ----------------------------------------------------------------------------
  val isCI = System.getenv("TRAVIS") == "true" || System.getenv("CI") == "true"
  val iterations = if (isCI) 1000 else 10000
  val timeout = if (isCI) 30.seconds else 10.seconds

  def cleanupOnError[A](task: IO[A], f: Fiber[IO, Throwable, _]) = f.cancel

  "Deferred — issue #380: producer keeps its thread, consumer stays forked" in {
    for (_ <- 0 until iterations) {
      val name = Thread.currentThread().getName

      def get(df: Deferred[IO, Unit]) =
        for {
          _ <- IO(Thread.currentThread().getName must not be equalTo(name))
          _ <- df.get
          _ <- IO(Thread.currentThread().getName must not be equalTo(name))
        } yield ()

      val task = for {
        df <- cats.effect.concurrent.Deferred[IO, Unit]
        fb <- get(df).start
        _ <- IO(Thread.currentThread().getName must be equalTo(name))
        _ <- df.complete(())
        _ <- IO(Thread.currentThread().getName must be equalTo(name))
        _ <- fb.join
      } yield ()

      task.unsafeRunTimed(timeout).nonEmpty must beTrue
    }

    success
  }

  "Deferred — issue #380: with foreverM" in {
    for (_ <- 0 until iterations) {
      val cancelLoop = new AtomicBoolean(false)
      val unit = IO {
        if (cancelLoop.get()) throw new CancellationException
      }

      try {
        val task = for {
          df <- cats.effect.concurrent.Deferred[IO, Unit]
          latch <- Deferred[IO, Unit]
          fb <- (latch.complete(()) *> df.get *> unit.foreverM).start
          _ <- latch.get
          _ <- cleanupOnError(timeout(df.complete(()),timeout), fb)
          _ <- fb.cancel
        } yield ()

        task.unsafeRunTimed(timeout).nonEmpty must beTrue
      } finally {
        cancelLoop.set(true)
      }
    }

    success
  }

  "Deferred — issue #380: with cooperative light async boundaries" in {
    def run = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == 512) IO.async[Unit](cb => IO(cb(Right(()))).as(None)) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)

      for {
        d <- Deferred[IO, Unit]
        latch <- Deferred[IO, Unit]
        fb <- (latch.complete(()) *> d.get *> foreverAsync(0)).start
        _ <- latch.get
        _ <- cleanupOnError(timeout(d.complete(()),timeout), fb)
        _ <- fb.cancel
      } yield true
    }

    for (_ <- 0 until iterations) {
      run.unsafeRunTimed(timeout).nonEmpty must beTrue
    }

    success
  }

  "Deferred — issue #380: with cooperative full async boundaries" in {
    def run = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == 512) IO.unit.start.flatMap(_.join) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)

      for {
        d <- Deferred[IO, Unit]
        latch <- Deferred[IO, Unit]
        fb <- (latch.complete(()) *> d.get *> foreverAsync(0)).start
        _ <- latch.get
        _ <- cleanupOnError(timeout(d.complete(()),timeout), fb)
        _ <- fb.cancel
      } yield true
    }

    for (_ <- 0 until iterations) {
      assert(run.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
    }

    success
  }

  //TODO remove once we have these as derived combinators again
  private def timeoutTo[F[_], E, A](fa: F[A], duration: FiniteDuration, fallback: F[A])(
    implicit F: Temporal[F, E]
  ): F[A] =
    F.race(fa, F.sleep(duration)).flatMap {
      case Left(a)  => F.pure(a)
      case Right(_) => fallback
    }

  private def timeout[F[_], A](fa: F[A], duration: FiniteDuration)(implicit F: Temporal[F, Throwable]): F[A] = {
    val timeoutException = F.raiseError[A](new RuntimeException(duration.toString))
    timeoutTo(fa, duration, timeoutException)
  }
}
