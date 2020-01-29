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

import java.util.concurrent.{ExecutorService, Executors, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import cats.effect.concurrent.{Deferred, Semaphore}
import cats.implicits._
import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration._
import scala.concurrent.{CancellationException, ExecutionContext}

class SemaphoreJVMParallelism1Tests extends BaseSemaphoreJVMTests(1)
class SemaphoreJVMParallelism2Tests extends BaseSemaphoreJVMTests(2)
class SemaphoreJVMParallelism4Tests extends BaseSemaphoreJVMTests(4)

abstract class BaseSemaphoreJVMTests(parallelism: Int) extends AnyFunSuite with Matchers with BeforeAndAfter {
  var service: ExecutorService = _

  implicit val context: ExecutionContext = new ExecutionContext {
    def execute(runnable: Runnable): Unit =
      service.execute(runnable)
    def reportFailure(cause: Throwable): Unit =
      cause.printStackTrace()
  }

  implicit val cs: ContextShift[IO] = IO.contextShift(context)
  implicit val timer: Timer[IO] = IO.timer(context)

  before {
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

  after {
    service.shutdown()
    assert(service.awaitTermination(60, TimeUnit.SECONDS), "has active threads")
  }

  // ----------------------------------------------------------------------------
  val isCI = System.getenv("TRAVIS") == "true" || System.getenv("CI") == "true"
  val iterations = if (isCI) 1000 else 10000
  val timeout = if (isCI) 30.seconds else 10.seconds

  test("Semaphore (concurrent) — issue #380: producer keeps its thread, consumer stays forked") {
    for (_ <- 0 until iterations) {
      val name = Thread.currentThread().getName

      def get(df: Semaphore[IO]) =
        for {
          _ <- IO(Thread.currentThread().getName shouldNot be(name))
          _ <- df.acquire
          _ <- IO(Thread.currentThread().getName shouldNot be(name))
        } yield ()

      val task = for {
        df <- cats.effect.concurrent.Semaphore[IO](0)
        fb <- get(df).start
        _ <- IO(Thread.currentThread().getName shouldBe name)
        _ <- df.release
        _ <- IO(Thread.currentThread().getName shouldBe name)
        _ <- fb.join
      } yield ()

      val dt = 10.seconds
      assert(task.unsafeRunTimed(dt).nonEmpty, s"; timed-out after $dt")
    }
  }

  test("Semaphore (concurrent) — issue #380: with foreverM; with latch") {
    for (_ <- 0 until iterations) {
      val cancelLoop = new AtomicBoolean(false)
      val unit = IO {
        if (cancelLoop.get()) throw new CancellationException
      }

      try {
        val task = for {
          df <- Semaphore[IO](0)
          latch <- Deferred[IO, Unit]
          fb <- (latch.complete(()) *> df.acquire *> unit.foreverM).start
          _ <- latch.get
          _ <- df.release.timeout(timeout).guarantee(fb.cancel)
        } yield ()

        assert(task.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
      } finally {
        cancelLoop.set(true)
      }
    }
  }

  test("Semaphore (concurrent) — issue #380: with foreverM; with no latch") {
    for (_ <- 0 until iterations) {
      val cancelLoop = new AtomicBoolean(false)
      val unit = IO {
        if (cancelLoop.get()) throw new CancellationException
      }

      try {
        val task = for {
          df <- Semaphore[IO](0)
          fb <- (df.acquire *> unit.foreverM).start
          _ <- df.release.timeout(timeout).guarantee(fb.cancel)
        } yield ()

        assert(task.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
      } finally {
        cancelLoop.set(true)
      }
    }
  }

  test("Semaphore (concurrent) — issue #380: with cooperative light async boundaries; with latch") {
    def run = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == 512) IO.async[Unit](cb => cb(Right(()))) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)

      for {
        d <- Semaphore[IO](0)
        latch <- Deferred[IO, Unit]
        fb <- (latch.complete(()) *> d.acquire *> foreverAsync(0)).start
        _ <- latch.get
        _ <- d.release.timeout(timeout).guarantee(fb.cancel)
      } yield true
    }

    for (_ <- 0 until iterations) {
      assert(run.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
    }
  }

  test("Semaphore (concurrent) — issue #380: with cooperative light async boundaries; with no latch") {
    def run = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == 512) IO.async[Unit](cb => cb(Right(()))) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)

      for {
        d <- Semaphore[IO](0)
        fb <- (d.acquire *> foreverAsync(0)).start
        _ <- d.release.timeout(timeout).guarantee(fb.cancel)
      } yield true
    }

    for (_ <- 0 until iterations) {
      assert(run.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
    }
  }

  test("Semaphore (concurrent) — issue #380: with cooperative full async boundaries; with latch") {
    def run = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == 512) IO.unit.start.flatMap(_.join) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)

      for {
        d <- Semaphore[IO](0)
        latch <- Deferred[IO, Unit]
        fb <- (latch.complete(()) *> d.acquire *> foreverAsync(0)).start
        _ <- latch.get
        _ <- d.release.timeout(timeout).guarantee(fb.cancel)
      } yield true
    }

    for (_ <- 0 until iterations) {
      assert(run.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
    }
  }

  test("Semaphore (concurrent) — issue #380: with cooperative full async boundaries; with no latch") {
    def run = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == 512) IO.unit.start.flatMap(_.join) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)

      for {
        d <- Semaphore[IO](0)
        fb <- (d.acquire *> foreverAsync(0)).start
        _ <- d.release.timeout(timeout).guarantee(fb.cancel)
      } yield true
    }

    for (_ <- 0 until iterations) {
      assert(run.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
    }
  }

  test("Semaphore (async) — issue #380: with foreverM and latch") {
    for (_ <- 0 until iterations) {
      val cancelLoop = new AtomicBoolean(false)
      val unit = IO {
        if (cancelLoop.get()) throw new CancellationException
      }

      try {
        val task = for {
          df <- Semaphore.uncancelable[IO](0)
          latch <- Deferred.uncancelable[IO, Unit]
          fb <- (latch.complete(()) *> df.acquire *> unit.foreverM).start
          _ <- latch.get
          _ <- df.release.timeout(timeout).guarantee(fb.cancel)
        } yield ()

        assert(task.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
      } finally {
        cancelLoop.set(true)
      }
    }
  }

  test("Semaphore (async) — issue #380: with foreverM and no latch") {
    for (_ <- 0 until iterations) {
      val cancelLoop = new AtomicBoolean(false)
      val unit = IO {
        if (cancelLoop.get()) throw new CancellationException
      }

      try {
        val task = for {
          df <- Semaphore.uncancelable[IO](0)
          fb <- (df.acquire *> unit.foreverM).start
          _ <- df.release.timeout(timeout).guarantee(fb.cancel)
        } yield ()

        assert(task.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
      } finally {
        cancelLoop.set(true)
      }
    }
  }

  test("Semaphore (async) — issue #380: with cooperative light async boundaries and latch") {
    def run = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == 512) IO.async[Unit](cb => cb(Right(()))) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)

      for {
        d <- Semaphore.uncancelable[IO](0)
        latch <- Deferred.uncancelable[IO, Unit]
        fb <- (latch.complete(()) *> d.acquire *> foreverAsync(0)).start
        _ <- latch.get
        _ <- d.release.timeout(timeout).guarantee(fb.cancel)
      } yield true
    }

    for (_ <- 0 until iterations) {
      assert(run.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
    }
  }

  test("Semaphore (async) — issue #380: with cooperative light async boundaries and no latch") {
    def run = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == 512) IO.async[Unit](cb => cb(Right(()))) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)

      for {
        d <- Semaphore.uncancelable[IO](0)
        fb <- (d.acquire *> foreverAsync(0)).start
        _ <- d.release.timeout(timeout).guarantee(fb.cancel)
      } yield true
    }

    for (_ <- 0 until iterations) {
      assert(run.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
    }
  }

  test("Semaphore (async) — issue #380: with cooperative full async boundaries and latch") {
    def run = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == 512) IO.unit.start.flatMap(_.join) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)

      for {
        d <- Semaphore.uncancelable[IO](0)
        latch <- Deferred.uncancelable[IO, Unit]
        fb <- (latch.complete(()) *> d.acquire *> foreverAsync(0)).start
        _ <- latch.get
        _ <- d.release.timeout(timeout).guarantee(fb.cancel)
      } yield true
    }

    for (_ <- 0 until iterations) {
      assert(run.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
    }
  }

  test("Semaphore (async) — issue #380: with cooperative full async boundaries and no latch") {
    def run = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == 512) IO.unit.start.flatMap(_.join) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)

      for {
        d <- Semaphore.uncancelable[IO](0)
        fb <- (d.acquire *> foreverAsync(0)).start
        _ <- d.release.timeout(timeout).guarantee(fb.cancel)
      } yield true
    }

    for (_ <- 0 until iterations) {
      assert(run.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
    }
  }
}
