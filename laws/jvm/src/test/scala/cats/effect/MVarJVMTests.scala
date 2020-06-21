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

import cats.effect.concurrent.{Deferred, MVar, MVar2}
import cats.implicits._
import org.scalatest.BeforeAndAfter
import org.scalatest.matchers.should.Matchers
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration._
import scala.concurrent.{CancellationException, ExecutionContext}

class MVarEmptyJVMParallelism1Tests extends BaseMVarJVMTests(1) {
  def allocate(implicit cs: ContextShift[IO]): IO[MVar2[IO, Unit]] =
    MVar.empty[IO, Unit]
  def allocateUncancelable: IO[MVar2[IO, Unit]] =
    MVar.uncancelableEmpty[IO, Unit]
  def acquire(ref: MVar2[IO, Unit]): IO[Unit] =
    ref.take
  def release(ref: MVar2[IO, Unit]): IO[Unit] =
    ref.put(())
}

class MVarEmptyJVMParallelism2Tests extends BaseMVarJVMTests(2) {
  def allocate(implicit cs: ContextShift[IO]): IO[MVar2[IO, Unit]] =
    MVar.empty[IO, Unit]
  def allocateUncancelable: IO[MVar2[IO, Unit]] =
    MVar.uncancelableEmpty[IO, Unit]
  def acquire(ref: MVar2[IO, Unit]): IO[Unit] =
    ref.take
  def release(ref: MVar2[IO, Unit]): IO[Unit] =
    ref.put(())
}

class MVarEmptyJVMParallelism4Tests extends BaseMVarJVMTests(4) {
  def allocate(implicit cs: ContextShift[IO]): IO[MVar2[IO, Unit]] =
    MVar.empty[IO, Unit]
  def allocateUncancelable: IO[MVar2[IO, Unit]] =
    MVar.uncancelableEmpty[IO, Unit]
  def acquire(ref: MVar2[IO, Unit]): IO[Unit] =
    ref.take
  def release(ref: MVar2[IO, Unit]): IO[Unit] =
    ref.put(())
}

// -----------------------------------------------------------------

class MVarFullJVMParallelism1Tests extends BaseMVarJVMTests(1) {
  def allocate(implicit cs: ContextShift[IO]): IO[MVar2[IO, Unit]] =
    MVar.of[IO, Unit](())
  def allocateUncancelable: IO[MVar2[IO, Unit]] =
    MVar.uncancelableOf[IO, Unit](())
  def acquire(ref: MVar2[IO, Unit]): IO[Unit] =
    ref.put(())
  def release(ref: MVar2[IO, Unit]): IO[Unit] =
    ref.take
}

class MVarFullJVMParallelism2Tests extends BaseMVarJVMTests(2) {
  def allocate(implicit cs: ContextShift[IO]): IO[MVar2[IO, Unit]] =
    MVar.of[IO, Unit](())
  def allocateUncancelable: IO[MVar2[IO, Unit]] =
    MVar.uncancelableOf[IO, Unit](())
  def acquire(ref: MVar2[IO, Unit]): IO[Unit] =
    ref.put(())
  def release(ref: MVar2[IO, Unit]): IO[Unit] =
    ref.take
}

class MVarFullJVMParallelism4Tests extends BaseMVarJVMTests(4) {
  def allocate(implicit cs: ContextShift[IO]): IO[MVar2[IO, Unit]] =
    MVar.of[IO, Unit](())
  def allocateUncancelable: IO[MVar2[IO, Unit]] =
    MVar.uncancelableOf[IO, Unit](())
  def acquire(ref: MVar2[IO, Unit]): IO[Unit] =
    ref.put(())
  def release(ref: MVar2[IO, Unit]): IO[Unit] =
    ref.take
}

// -----------------------------------------------------------------

abstract class BaseMVarJVMTests(parallelism: Int) extends AnyFunSuite with Matchers with BeforeAndAfter {
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

  def allocate(implicit cs: ContextShift[IO]): IO[MVar2[IO, Unit]]
  def allocateUncancelable: IO[MVar2[IO, Unit]]
  def acquire(ref: MVar2[IO, Unit]): IO[Unit]
  def release(ref: MVar2[IO, Unit]): IO[Unit]

  // ----------------------------------------------------------------------------

  test("MVar (concurrent) — issue #380: producer keeps its thread, consumer stays forked") {
    for (_ <- 0 until iterations) {
      val name = Thread.currentThread().getName

      def get(df: MVar2[IO, Unit]) =
        for {
          _ <- IO(Thread.currentThread().getName shouldNot be(name))
          _ <- acquire(df)
          _ <- IO(Thread.currentThread().getName shouldNot be(name))
        } yield ()

      val task = for {
        df <- allocate
        fb <- get(df).start
        _ <- IO(Thread.currentThread().getName shouldBe name)
        _ <- release(df)
        _ <- IO(Thread.currentThread().getName shouldBe name)
        _ <- fb.join
      } yield ()

      assert(task.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
    }
  }

  test("MVar (concurrent) — issue #380: with foreverM; with latch") {
    for (_ <- 0 until iterations) {
      val cancelLoop = new AtomicBoolean(false)
      val unit = IO {
        if (cancelLoop.get()) throw new CancellationException
      }

      try {
        val task = for {
          df <- allocate
          latch <- Deferred.uncancelable[IO, Unit]
          fb <- (latch.complete(()) *> acquire(df) *> unit.foreverM).start
          _ <- latch.get
          _ <- release(df).timeout(timeout).guarantee(fb.cancel)
        } yield ()

        assert(task.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
      } finally {
        cancelLoop.set(true)
      }
    }
  }

  test("MVar (concurrent) — issue #380: with foreverM; without latch") {
    for (_ <- 0 until iterations) {
      val cancelLoop = new AtomicBoolean(false)
      val unit = IO {
        if (cancelLoop.get()) throw new CancellationException
      }

      try {
        val task = for {
          df <- allocate
          fb <- (acquire(df) *> unit.foreverM).start
          _ <- release(df).timeout(timeout).guarantee(fb.cancel)
        } yield ()

        assert(task.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
      } finally {
        cancelLoop.set(true)
      }
    }
  }

  test("MVar (concurrent) — issue #380: with cooperative light async boundaries; with latch") {
    def run = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == 512) IO.async[Unit](cb => cb(Right(()))) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)

      for {
        d <- allocate
        latch <- Deferred.uncancelable[IO, Unit]
        fb <- (latch.complete(()) *> acquire(d) *> foreverAsync(0)).start
        _ <- latch.get
        _ <- release(d).timeout(5.seconds).guarantee(fb.cancel)
      } yield true
    }

    for (_ <- 0 until iterations) {
      assert(run.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
    }
  }

  test("MVar (concurrent) — issue #380: with cooperative light async boundaries; without latch") {
    def run = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == 512) IO.async[Unit](cb => cb(Right(()))) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)

      for {
        d <- allocate
        fb <- (acquire(d) *> foreverAsync(0)).start
        _ <- release(d).timeout(5.seconds).guarantee(fb.cancel)
      } yield true
    }

    for (_ <- 0 until iterations) {
      assert(run.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
    }
  }

  test("MVar (concurrent) — issue #380: with cooperative full async boundaries; with latch") {
    def run = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == 512) IO.unit.start.flatMap(_.join) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)

      for {
        d <- allocate
        latch <- Deferred.uncancelable[IO, Unit]
        fb <- (latch.complete(()) *> acquire(d) *> foreverAsync(0)).start
        _ <- latch.get
        _ <- release(d).timeout(timeout).guarantee(fb.cancel)
      } yield true
    }

    for (_ <- 0 until iterations) {
      assert(run.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
    }
  }

  test("MVar (concurrent) — issue #380: with cooperative full async boundaries; without latch") {
    def run = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == 512) IO.unit.start.flatMap(_.join) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)

      for {
        d <- allocate
        fb <- (acquire(d) *> foreverAsync(0)).start
        _ <- release(d).timeout(timeout).guarantee(fb.cancel)
      } yield true
    }

    for (_ <- 0 until iterations) {
      assert(run.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
    }
  }

  test("MVar (async) — issue #380: with foreverM; with latch") {
    for (_ <- 0 until iterations) {
      val cancelLoop = new AtomicBoolean(false)
      val unit = IO {
        if (cancelLoop.get()) throw new CancellationException
      }

      try {
        val task = for {
          df <- allocateUncancelable
          latch <- Deferred[IO, Unit]
          fb <- (latch.complete(()) *> acquire(df) *> unit.foreverM).start
          _ <- latch.get
          _ <- release(df).timeout(timeout).guarantee(fb.cancel)
        } yield ()

        assert(task.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
      } finally {
        cancelLoop.set(true)
      }
    }
  }

  test("MVar (async) — issue #380: with foreverM; without latch") {
    for (_ <- 0 until iterations) {
      val cancelLoop = new AtomicBoolean(false)
      val unit = IO {
        if (cancelLoop.get()) throw new CancellationException
      }

      try {
        val task = for {
          df <- allocateUncancelable
          fb <- (acquire(df) *> unit.foreverM).start
          _ <- release(df).timeout(timeout).guarantee(fb.cancel)
        } yield ()

        assert(task.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
      } finally {
        cancelLoop.set(true)
      }
    }
  }

  test("MVar (async) — issue #380: with cooperative light async boundaries; with latch") {
    def run = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == 512) IO.async[Unit](cb => cb(Right(()))) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)

      for {
        d <- allocateUncancelable
        latch <- Deferred.uncancelable[IO, Unit]
        fb <- (latch.complete(()) *> acquire(d) *> foreverAsync(0)).start
        _ <- latch.get
        _ <- release(d).timeout(timeout).guarantee(fb.cancel)
      } yield true
    }

    for (_ <- 0 until iterations) {
      assert(run.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
    }
  }

  test("MVar (async) — issue #380: with cooperative light async boundaries; without latch") {
    def run = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == 512) IO.async[Unit](cb => cb(Right(()))) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)

      for {
        d <- allocateUncancelable
        fb <- (acquire(d) *> foreverAsync(0)).start
        _ <- release(d).timeout(timeout).guarantee(fb.cancel)
      } yield true
    }

    for (_ <- 0 until iterations) {
      assert(run.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
    }
  }

  test("MVar (async) — issue #380: with cooperative full async boundaries; with latch") {
    def run = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == 512) IO.unit.start.flatMap(_.join) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)

      for {
        d <- allocateUncancelable
        latch <- Deferred.uncancelable[IO, Unit]
        fb <- (latch.complete(()) *> acquire(d) *> foreverAsync(0)).start
        _ <- latch.get
        _ <- release(d).timeout(timeout).guarantee(fb.cancel)
      } yield true
    }

    for (_ <- 0 until iterations) {
      assert(run.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
    }
  }
}
