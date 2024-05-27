/*
 * Copyright 2020-2024 Typelevel
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

import cats.effect.std.Semaphore
import cats.effect.unsafe.{
  IORuntime,
  IORuntimeConfig,
  PollingSystem,
  SleepSystem,
  WorkStealingThreadPool
}
import cats.syntax.all._

import org.scalacheck.Prop.forAll
import org.specs2.ScalaCheck

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.util.concurrent.{
  CancellationException,
  CompletableFuture,
  CountDownLatch,
  ExecutorService,
  Executors,
  ThreadLocalRandom
}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicLong, AtomicReference}

trait IOPlatformSpecification extends DetectPlatform { self: BaseSpec with ScalaCheck =>

  def platformSpecs = {
    "platform" should {

      "shift delay evaluation within evalOn" in real {
        val Exec1Name = "testing executor 1"
        val exec1 = Executors.newSingleThreadExecutor { r =>
          val t = new Thread(r)
          t.setName(Exec1Name)
          t
        }

        val Exec2Name = "testing executor 2"
        val exec2 = Executors.newSingleThreadExecutor { r =>
          val t = new Thread(r)
          t.setName(Exec2Name)
          t
        }

        val Exec3Name = "testing executor 3"
        val exec3 = Executors.newSingleThreadExecutor { r =>
          val t = new Thread(r)
          t.setName(Exec3Name)
          t
        }

        val nameF = IO(Thread.currentThread().getName())

        val test = nameF flatMap { outer1 =>
          val inner1F = nameF flatMap { inner1 =>
            val inner2F = nameF map { inner2 => (outer1, inner1, inner2) }

            inner2F.evalOn(ExecutionContext.fromExecutor(exec2))
          }

          inner1F.evalOn(ExecutionContext.fromExecutor(exec1)).flatMap {
            case (outer1, inner1, inner2) =>
              nameF.map(outer2 => (outer1, inner1, inner2, outer2))
          }
        }

        test.evalOn(ExecutionContext.fromExecutor(exec3)).flatMap { result =>
          IO {
            result mustEqual ((Exec3Name, Exec1Name, Exec2Name, Exec3Name))
          }
        }
      }

      "start 1000 fibers in parallel and await them all" in real {
        val input = (0 until 1000).toList

        val ioa = for {
          fibers <- input.traverse(i => IO.pure(i).start)
          _ <- fibers.traverse_(_.join.void)
        } yield ()

        ioa.as(ok)
      }

      "start 1000 fibers in series and await them all" in real {
        val input = (0 until 1000).toList
        val ioa = input.traverse(i => IO.pure(i).start.flatMap(_.join))

        ioa.as(ok)
      }

      "race many things" in real {
        val task = (0 until 100).foldLeft(IO.never[Int]) { (acc, _) =>
          IO.race(acc, IO(1)).map {
            case Left(i) => i
            case Right(i) => i
          }
        }

        task.replicateA(100).as(ok)
      }

      "round trip non-canceled through j.u.c.CompletableFuture" in ticked { implicit ticker =>
        forAll { (ioa: IO[Int]) =>
          val normalized = ioa.onCancel(IO.never)
          normalized.eqv(IO.fromCompletableFuture(IO(normalized.unsafeToCompletableFuture())))
        }
      }

      "canceled through j.u.c.CompletableFuture is errored" in ticked { implicit ticker =>
        val test =
          IO.fromCompletableFuture(IO(IO.canceled.as(-1).unsafeToCompletableFuture()))
            .handleError(_ => 42)

        test must completeAs(42)
      }

      "errors in j.u.c.CompletableFuture are not wrapped" in ticked { implicit ticker =>
        val e = new RuntimeException("stuff happened")
        val test = IO
          .fromCompletableFuture[Int](IO {
            val root = new CompletableFuture[Int]
            root.completeExceptionally(e)
            root.thenApply(_ + 1)
          })
          .attempt

        test must completeAs(Left(e))
      }

      "interrupt well-behaved blocking synchronous effect" in real {
        var interrupted = true
        val latch = new CountDownLatch(1)

        val await = IO.interruptible {
          latch.countDown()
          Thread.sleep(15000)
          interrupted = false
        }

        for {
          f <- await.start
          _ <- IO.blocking(latch.await())
          _ <- f.cancel
          _ <- IO(interrupted must beTrue)
        } yield ok
      }

      "interrupt ill-behaved blocking synchronous effect" in real {
        var interrupted = true
        val latch = new CountDownLatch(1)

        val await = IO.interruptibleMany {
          latch.countDown()

          try {
            Thread.sleep(15000)
          } catch {
            case _: InterruptedException => ()
          }

          // psych!
          try {
            Thread.sleep(15000)
          } catch {
            case _: InterruptedException => ()
          }

          // I AM INVINCIBLE
          Thread.sleep(15000)

          interrupted = false
        }

        for {
          f <- await.start
          _ <- IO.blocking(latch.await())
          _ <- f.cancel
          _ <- IO(interrupted must beTrue)
        } yield ok
      }

      "auto-cede" in real {
        val forever = IO.unit.foreverM

        val ec = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())

        val run = for {
          // Run in a tight loop on single-threaded ec so only hope of
          // seeing cancelation status is auto-cede
          fiber <- forever.start
          // Allow the tight loop to be scheduled
          _ <- IO.sleep(5.millis)
          // Only hope for the cancelation being run is auto-yielding
          _ <- fiber.cancel
        } yield true

        run.evalOn(ec).guarantee(IO(ec.shutdown())).flatMap { res => IO(res must beTrue) }
      }

      "realTimeInstant should return an Instant constructed from realTime" in ticked {
        implicit ticker =>
          val op = for {
            now <- IO.realTimeInstant
            realTime <- IO.realTime
          } yield now.toEpochMilli == realTime.toMillis

          op must completeAs(true)
      }

      "cancel all inner effects when canceled" in ticked { implicit ticker =>
        val deadlock = for {
          gate1 <- Semaphore[IO](2)
          _ <- gate1.acquireN(2)

          gate2 <- Semaphore[IO](2)
          _ <- gate2.acquireN(2)

          io = IO {
            // these finalizers never return, so this test is intentionally designed to hang
            // they flip their gates first though; this is just testing that both run in parallel
            val a = (gate1.release *> IO.never) onCancel {
              gate2.release *> IO.never
            }

            val b = (gate1.release *> IO.never) onCancel {
              gate2.release *> IO.never
            }

            a.unsafeRunAndForget()
            b.unsafeRunAndForget()
          }

          _ <- io.flatMap(_ => gate1.acquireN(2)).start
          _ <- gate2.acquireN(2) // if both are not run in parallel, then this will hang
        } yield ()

        val test = for {
          t <- IO(deadlock.unsafeToFutureCancelable())
          (f, ct) = t
          _ <- IO.fromFuture(IO(ct()))
          _ <- IO.blocking(scala.concurrent.Await.result(f, Duration.Inf))
        } yield ()

        test.attempt.map {
          case Left(t) => t.isInstanceOf[CancellationException]
          case Right(_) => false
        } must completeAs(true)
      }

      "run a timer which crosses into a blocking region" in realWithRuntime { rt =>
        rt.scheduler match {
          case sched: WorkStealingThreadPool[_] =>
            // we structure this test by calling the runtime directly to avoid nondeterminism
            val delay = IO.async[Unit] { cb =>
              IO {
                // register a timer (use sleepInternal to ensure we get the worker-local version)
                val cancel = sched.sleepInternal(1.second, cb)

                // convert the worker to a blocker
                scala.concurrent.blocking(())

                Some(IO(cancel.run()))
              }
            }

            // if the timer fires correctly, the timeout will not be hit
            delay.race(IO.sleep(2.seconds)).flatMap(res => IO(res must beLeft)).map(_.toResult)

          case _ => IO.pure(skipped("test not running against WSTP"))
        }
      }

      "run timers exactly once when crossing into a blocking region" in realWithRuntime { rt =>
        rt.scheduler match {
          case sched: WorkStealingThreadPool[_] =>
            IO defer {
              val ai = new AtomicInteger(0)

              sched.sleepInternal(500.millis, { _ => ai.getAndIncrement(); () })

              // if we aren't careful, this conversion can duplicate the timer
              scala.concurrent.blocking {
                IO.sleep(1.second) >> IO(ai.get() mustEqual 1).map(_.toResult)
              }
            }

          case _ => IO.pure(skipped("test not running against WSTP"))
        }
      }

      "run a timer registered on a blocker" in realWithRuntime { rt =>
        rt.scheduler match {
          case sched: WorkStealingThreadPool[_] =>
            // we structure this test by calling the runtime directly to avoid nondeterminism
            val delay = IO.async[Unit] { cb =>
              IO {
                scala.concurrent.blocking {
                  // register a timer (use sleepInternal to ensure we get the worker-local version)
                  val cancel = sched.sleepInternal(1.second, cb)
                  Some(IO(cancel.run()))
                }
              }
            }

            // if the timer fires correctly, the timeout will not be hit
            delay.race(IO.sleep(2.seconds)).flatMap(res => IO(res must beLeft)).map(_.toResult)

          case _ => IO.pure(skipped("test not running against WSTP"))
        }
      }

      "safely detect hard-blocked threads even while blockers are being created" in {
        val (compute, _, shutdown) =
          IORuntime.createWorkStealingComputeThreadPool(blockedThreadDetectionEnabled = true)

        implicit val runtime: IORuntime =
          IORuntime.builder().setCompute(compute, shutdown).build()

        try {
          val test = for {
            _ <- IO.unit.foreverM.start.replicateA_(200)
            _ <- 0.until(200).toList.parTraverse_(_ => IO.blocking(()))
          } yield ok // we can't actually test this directly because the symptom is vaporizing a worker

          test.unsafeRunSync()
        } finally {
          runtime.shutdown()
        }
      }

      // this test ensures that the parkUntilNextSleeper bit works
      "run a timer when parking thread" in {
        val (pool, _, shutdown) = IORuntime.createWorkStealingComputeThreadPool(threads = 1)

        implicit val runtime: IORuntime = IORuntime.builder().setCompute(pool, shutdown).build()

        try {
          // longer sleep all-but guarantees this timer is fired *after* the worker is parked
          val test = IO.sleep(500.millis) *> IO.pure(true)
          test.unsafeRunTimed(5.seconds) must beSome(true)
        } finally {
          runtime.shutdown()
        }
      }

      // this test ensures that we always see the timer, even when it fires just as we're about to park
      "run a timer when detecting just prior to park" in {
        val (pool, _, shutdown) = IORuntime.createWorkStealingComputeThreadPool(threads = 1)

        implicit val runtime: IORuntime = IORuntime.builder().setCompute(pool, shutdown).build()

        try {
          // shorter sleep makes it more likely this timer fires *before* the worker is parked
          val test = IO.sleep(1.milli) *> IO.pure(true)
          test.unsafeRunTimed(1.second) must beSome(true)
        } finally {
          runtime.shutdown()
        }
      }

      "random racing sleeps" in real {
        def randomSleep: IO[Unit] = IO.defer {
          val n = ThreadLocalRandom.current().nextInt(2000000)
          IO.sleep(n.micros) // less than 2 seconds
        }

        def raceAll(ios: List[IO[Unit]]): IO[Unit] = {
          ios match {
            case head :: tail => tail.foldLeft(head) { (x, y) => IO.race(x, y).void }
            case Nil => IO.unit
          }
        }

        // we race a lot of "sleeps", it must not hang
        // (this includes inserting and cancelling
        // a lot of callbacks into the heap,
        // thus hopefully stressing the data structure):
        List
          .fill(500) {
            raceAll(List.fill(500) { randomSleep })
          }
          .parSequence_
          .as(ok)
      }

      "steal timers" in realWithRuntime { rt =>
        val spin = IO.cede *> IO { // first make sure we're on a `WorkerThread`
          // The `WorkerThread` which executes this IO
          // will never exit the `while` loop, unless
          // the timer is triggered, so it will never
          // be able to trigger the timer itself. The
          // only way this works is if some other worker
          // steals the the timer.
          val flag = new AtomicBoolean(false)
          val _ = rt.scheduler.sleep(500.millis, () => { flag.set(true) })
          var ctr = 0L
          while (!flag.get()) {
            if ((ctr % 8192L) == 0L) {
              // Make sure there is another unparked
              // worker searching (and stealing timers):
              rt.compute.execute(() => { () })
            }
            ctr += 1L
          }
        }

        spin.as(ok)
      }

      "lots of externally-canceled timers" in real {
        Resource
          .make(IO(Executors.newSingleThreadExecutor()))(exec => IO(exec.shutdownNow()).void)
          .map(ExecutionContext.fromExecutor(_))
          .use { ec =>
            IO.sleep(1.day).start.flatMap(_.cancel.evalOn(ec)).parReplicateA_(100000)
          }
          .as(ok)
      }

      "not lose cedeing threads from the bypass when blocker transitioning" in {
        // writing this test in terms of IO seems to not reproduce the issue
        0.until(5) foreach { _ =>
          val wstp = new WorkStealingThreadPool[AnyRef](
            threadCount = 2,
            threadPrefix = "testWorker",
            blockerThreadPrefix = "testBlocker",
            runtimeBlockingExpiration = 3.seconds,
            reportFailure0 = _.printStackTrace(),
            blockedThreadDetectionEnabled = false,
            shutdownTimeout = 1.second,
            system = SleepSystem
          )

          val runtime = IORuntime
            .builder()
            .setCompute(wstp, () => wstp.shutdown())
            .setConfig(IORuntimeConfig(cancelationCheckThreshold = 1, autoYieldThreshold = 2))
            .build()

          try {
            val ctr = new AtomicLong

            val tsk1 = IO { ctr.incrementAndGet() }.foreverM
            val fib1 = tsk1.unsafeRunFiber((), _ => (), { (_: Any) => () })(runtime)
            for (_ <- 1 to 10) {
              val tsk2 = IO.blocking { Thread.sleep(5L) }
              tsk2.unsafeRunFiber((), _ => (), _ => ())(runtime)
            }
            fib1.join.unsafeRunFiber((), _ => (), _ => ())(runtime)

            Thread.sleep(1000L)
            val results = 0.until(3).toList map { _ =>
              Thread.sleep(100L)
              ctr.get()
            }

            results must not[List[Long]](beEqualTo(List.fill(3)(results.head)))
          } finally {
            runtime.shutdown()
          }
        }

        ok
      }

      "wake parked thread for polled events" in {

        trait DummyPoller {
          def poll: IO[Unit]
        }

        object DummySystem extends PollingSystem {
          type Api = DummyPoller
          type Poller = AtomicReference[List[Either[Throwable, Unit] => Unit]]

          def close() = ()

          def makePoller() = new AtomicReference(List.empty[Either[Throwable, Unit] => Unit])
          def needsPoll(poller: Poller) = poller.get.nonEmpty
          def closePoller(poller: Poller) = ()

          def interrupt(targetThread: Thread, targetPoller: Poller) =
            SleepSystem.interrupt(targetThread, SleepSystem.makePoller())

          def poll(poller: Poller, nanos: Long, reportFailure: Throwable => Unit) = {
            poller.getAndSet(Nil) match {
              case Nil =>
                SleepSystem.poll(SleepSystem.makePoller(), nanos, reportFailure)
              case cbs =>
                cbs.foreach(_.apply(Right(())))
                true
            }
          }

          def makeApi(access: (Poller => Unit) => Unit): DummySystem.Api =
            new DummyPoller {
              def poll = IO.async_[Unit] { cb =>
                access { poller =>
                  poller.getAndUpdate(cb :: _)
                  ()
                }
              }
            }
        }

        val (pool, poller, shutdown) = IORuntime.createWorkStealingComputeThreadPool(
          threads = 2,
          pollingSystem = DummySystem)

        implicit val runtime: IORuntime =
          IORuntime.builder().setCompute(pool, shutdown).addPoller(poller, () => ()).build()

        try {
          val test =
            IO.pollers.map(_.head.asInstanceOf[DummyPoller]).flatMap { poller =>
              val blockAndPoll = IO.blocking(Thread.sleep(10)) *> poller.poll
              blockAndPoll.replicateA(100).as(true)
            }
          test.unsafeRunSync() must beTrue
        } finally {
          runtime.shutdown()
        }
      }

      if (javaMajorVersion >= 21)
        "block in-place on virtual threads" in real {
          val loomExec = classOf[Executors]
            .getDeclaredMethod("newVirtualThreadPerTaskExecutor")
            .invoke(null)
            .asInstanceOf[ExecutorService]

          val loomEc = ExecutionContext.fromExecutor(loomExec)

          IO.blocking {
            classOf[Thread]
              .getDeclaredMethod("isVirtual")
              .invoke(Thread.currentThread())
              .asInstanceOf[Boolean]
          }.evalOn(loomEc)
        }
      else
        "block in-place on virtual threads" in skipped("virtual threads not supported")
    }
  }
}
