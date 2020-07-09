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

// package cats.effect

// import java.util.concurrent.{ExecutorService, Executors, ThreadFactory, TimeUnit}
// import concurrent.Deferred
// import cats.implicits._
// import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
// import org.scalatest.BeforeAndAfter
// import org.scalatest.matchers.should.Matchers
// import org.scalatest.funsuite.AnyFunSuite
// import scala.concurrent.duration._
// import scala.concurrent.{CancellationException, ExecutionContext}

// class DeferredJVMParallelism1Tests extends BaseDeferredJVMTests(1)
// class DeferredJVMParallelism2Tests extends BaseDeferredJVMTests(2)
// class DeferredJVMParallelism4Tests extends BaseDeferredJVMTests(4)

// abstract class BaseDeferredJVMTests(parallelism: Int) extends AnyFunSuite with Matchers with BeforeAndAfter {
//   var service: ExecutorService = _

//   implicit val context: ExecutionContext = new ExecutionContext {
//     def execute(runnable: Runnable): Unit =
//       service.execute(runnable)
//     def reportFailure(cause: Throwable): Unit =
//       cause.printStackTrace()
//   }

//   implicit val cs: ContextShift[IO] = IO.contextShift(context)
//   implicit val timer: Timer[IO] = IO.timer(context)

//   before {
//     service = Executors.newFixedThreadPool(
//       parallelism,
//       new ThreadFactory {
//         private[this] val index = new AtomicLong(0)
//         def newThread(r: Runnable): Thread = {
//           val th = new Thread(r)
//           th.setName(s"semaphore-tests-${index.getAndIncrement()}")
//           th.setDaemon(false)
//           th
//         }
//       }
//     )
//   }

//   after {
//     service.shutdown()
//     assert(service.awaitTermination(60, TimeUnit.SECONDS), "has active threads")
//   }

//   // ----------------------------------------------------------------------------
//   val isCI = System.getenv("TRAVIS") == "true" || System.getenv("CI") == "true"
//   val iterations = if (isCI) 1000 else 10000
//   val timeout = if (isCI) 30.seconds else 10.seconds

//   def cleanupOnError[A](task: IO[A], f: Fiber[IO, _]) =
//     task.guaranteeCase {
//       case ExitCase.Canceled | ExitCase.Error(_) =>
//         f.cancel
//       case _ =>
//         IO.unit
//     }

//   test("Deferred (concurrent) — issue #380: producer keeps its thread, consumer stays forked") {
//     for (_ <- 0 until iterations) {
//       val name = Thread.currentThread().getName

//       def get(df: Deferred[IO, Unit]) =
//         for {
//           _ <- IO(Thread.currentThread().getName shouldNot be(name))
//           _ <- df.get
//           _ <- IO(Thread.currentThread().getName shouldNot be(name))
//         } yield ()

//       val task = for {
//         df <- cats.effect.concurrent.Deferred[IO, Unit]
//         fb <- get(df).start
//         _ <- IO(Thread.currentThread().getName shouldBe name)
//         _ <- df.complete(())
//         _ <- IO(Thread.currentThread().getName shouldBe name)
//         _ <- fb.join
//       } yield ()

//       assert(task.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
//     }
//   }

//   test("Deferred (concurrent) — issue #380: with foreverM") {
//     for (_ <- 0 until iterations) {
//       val cancelLoop = new AtomicBoolean(false)
//       val unit = IO {
//         if (cancelLoop.get()) throw new CancellationException
//       }

//       try {
//         val task = for {
//           df <- cats.effect.concurrent.Deferred[IO, Unit]
//           latch <- Deferred[IO, Unit]
//           fb <- (latch.complete(()) *> df.get *> unit.foreverM).start
//           _ <- latch.get
//           _ <- cleanupOnError(df.complete(()).timeout(timeout), fb)
//           _ <- fb.cancel
//         } yield ()

//         assert(task.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
//       } finally {
//         cancelLoop.set(true)
//       }
//     }
//   }

//   test("Deferred (concurrent) — issue #380: with cooperative light async boundaries") {
//     def run = {
//       def foreverAsync(i: Int): IO[Unit] =
//         if (i == 512) IO.async[Unit](cb => cb(Right(()))) >> foreverAsync(0)
//         else IO.unit >> foreverAsync(i + 1)

//       for {
//         d <- Deferred[IO, Unit]
//         latch <- Deferred[IO, Unit]
//         fb <- (latch.complete(()) *> d.get *> foreverAsync(0)).start
//         _ <- latch.get
//         _ <- cleanupOnError(d.complete(()).timeout(timeout), fb)
//         _ <- fb.cancel
//       } yield true
//     }

//     for (_ <- 0 until iterations) {
//       assert(run.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
//     }
//   }

//   test("Deferred (concurrent) — issue #380: with cooperative full async boundaries") {
//     def run = {
//       def foreverAsync(i: Int): IO[Unit] =
//         if (i == 512) IO.unit.start.flatMap(_.join) >> foreverAsync(0)
//         else IO.unit >> foreverAsync(i + 1)

//       for {
//         d <- Deferred[IO, Unit]
//         latch <- Deferred[IO, Unit]
//         fb <- (latch.complete(()) *> d.get *> foreverAsync(0)).start
//         _ <- latch.get
//         _ <- cleanupOnError(d.complete(()).timeout(timeout), fb)
//         _ <- fb.cancel
//       } yield true
//     }

//     for (_ <- 0 until iterations) {
//       assert(run.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
//     }
//   }

//   test("Deferred (async) — issue #380: with foreverM") {
//     for (_ <- 0 until iterations) {
//       val cancelLoop = new AtomicBoolean(false)
//       val unit = IO {
//         if (cancelLoop.get()) throw new CancellationException
//       }

//       try {
//         val task = for {
//           df <- cats.effect.concurrent.Deferred.uncancelable[IO, Unit]
//           f <- (df.get *> unit.foreverM).start
//           _ <- df.complete(())
//           _ <- f.cancel
//         } yield ()

//         assert(task.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
//       } finally {
//         cancelLoop.set(true)
//       }
//     }
//   }

//   test("Deferred (async) — issue #380: with cooperative light async boundaries") {
//     def run = {
//       def foreverAsync(i: Int): IO[Unit] =
//         if (i == 512) IO.async[Unit](cb => cb(Right(()))) >> foreverAsync(0)
//         else IO.unit >> foreverAsync(i + 1)

//       for {
//         d <- Deferred.uncancelable[IO, Unit]
//         latch <- Deferred.uncancelable[IO, Unit]
//         fb <- (latch.complete(()) *> d.get *> foreverAsync(0)).start
//         _ <- latch.get
//         _ <- d.complete(()).timeout(timeout).guarantee(fb.cancel)
//       } yield true
//     }

//     for (_ <- 0 until iterations) {
//       assert(run.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
//     }
//   }

//   test("Deferred (async) — issue #380: with cooperative full async boundaries") {
//     def run = {
//       def foreverAsync(i: Int): IO[Unit] =
//         if (i == 512) IO.unit.start.flatMap(_.join) >> foreverAsync(0)
//         else IO.unit >> foreverAsync(i + 1)

//       for {
//         d <- Deferred.uncancelable[IO, Unit]
//         latch <- Deferred.uncancelable[IO, Unit]
//         fb <- (latch.complete(()) *> d.get *> foreverAsync(0)).start
//         _ <- latch.get
//         _ <- d.complete(()).timeout(5.seconds).guarantee(fb.cancel)
//       } yield true
//     }

//     for (_ <- 0 until iterations) {
//       assert(run.unsafeRunTimed(timeout).nonEmpty, s"; timed-out after $timeout")
//     }
//   }
// }
