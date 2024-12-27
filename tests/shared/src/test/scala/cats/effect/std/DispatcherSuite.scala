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
import cats.effect.testkit.TestControl
import cats.syntax.all._

import scala.concurrent.{ExecutionContext, Promise}
import scala.concurrent.duration._

import java.util.concurrent.atomic.AtomicInteger

class DispatcherSuite extends BaseSuite with DetectPlatform {

  override def executionTimeout = 30.seconds

  {
    val D = Dispatcher.sequential[IO](await = true)

    sequential("sequential dispatcher (cancelable = false) - await = true", D, false)

    awaitTermination("sequential dispatcher (cancelable = false) - await = true", D)

    real("sequential dispatcher (cancelable = false) - await = true - not hang") {
      D.use(dispatcher => IO(dispatcher.unsafeRunAndForget(IO.unit)))
        .replicateA(if (isJS || isNative) 1 else 10000)
        .as(true)
    }

    real(
      "sequential dispatcher (cancelable = false) - await = true - await work queue drain on shutdown") {
      val count = 1000

      IO.ref(0) flatMap { resultsR =>
        val increments = D use { runner =>
          IO {
            0.until(count).foreach(_ => runner.unsafeRunAndForget(resultsR.update(_ + 1)))
          }
        }

        increments *> resultsR.get.flatMap(r => IO(assertEquals(r, count)))
      }
    }

    real(
      "sequential dispatcher (cancelable = false) - await = true - terminating worker preserves task order") {
      val count = 10

      IO.ref(Vector[Int]()) flatMap { resultsR =>
        val appends = D use { runner =>
          IO {
            0.until(count).foreach(i => runner.unsafeRunAndForget(resultsR.update(_ :+ i)))
          }
        }

        appends *> resultsR.get.flatMap(r => IO(assertEquals(r, 0.until(count).toVector)))
      }
    }

    real(
      "sequential dispatcher (cancelable = false) - await = true - correctly backpressure cancellation") {
      D.use { dispatcher =>
        IO.ref(0).flatMap { ctr1 =>
          IO.ref(0).flatMap { ctr2 =>
            IO.fromFuture(IO {
              val (_, cancel) = dispatcher.unsafeToFutureCancelable(IO.uncancelable { _ =>
                ctr1.update(_ + 1) *> IO.sleep(0.1.second) *> ctr2.update(_ + 1)
              })
              val cancelFut = cancel()
              cancelFut
            }).flatMap { _ =>
              // if we're here, `cancel()` finished, so
              // either the task didn't run at all (i.e.,
              // it was cancelled before starting), or
              // it ran and already finished completely:
              (ctr1.get, ctr2.get).flatMapN { (v1, v2) => IO(assertEquals(v1, v2)) }
            }
          }
        }
      }.replicateA_(if (isJVM) 10000 else 1)

    }
  }

  {
    val D = Dispatcher.sequential[IO](await = false)

    sequential("sequential dispatcher (cancelable = false) - await = false", D, false)

    real(
      "sequential dispatcher (cancelable = false) - await = false - cancel all inner effects when canceled") {
      var canceled = false

      val body = D use { runner =>
        IO(runner.unsafeRunAndForget(IO.never.onCancel(IO { canceled = true }))) *> IO.never
      }

      val action = body.start.flatMap(f => IO.sleep(500.millis) *> f.cancel)

      TestControl.executeEmbed(action *> IO(assert(canceled)))
    }
  }

  {
    val D = Dispatcher.sequentialCancelable[IO](await = true)

    sequential("sequential dispatcher (cancelable = true) - await = true", D, true)

    awaitTermination("sequential dispatcher (cancelable = true) - await = true", D)

    real("sequential dispatcher (cancelable = true) - await = true - not hang") {
      D.use(dispatcher => IO(dispatcher.unsafeRunAndForget(IO.unit)))
        .replicateA(if (isJS || isNative) 1 else 10000)
        .as(true)
    }
  }

  {
    val D = Dispatcher.sequentialCancelable[IO](await = false)

    sequential("sequential dispatcher (cancelable = true) - await = false", D, true)

    real(
      "sequential dispatcher (cancelable = true) - await = false - cancel all inner effects when canceled") {
      var canceled = false

      val body = D use { runner =>
        IO(runner.unsafeRunAndForget(IO.never.onCancel(IO { canceled = true }))) *> IO.never
      }

      val action = body.start.flatMap(f => IO.sleep(500.millis) *> f.cancel)

      TestControl.executeEmbed(action *> IO(assert(canceled)))
    }
  }

  private def sequential(
      name: String,
      dispatcher: Resource[IO, Dispatcher[IO]],
      cancelable: Boolean) = {

    common(name, dispatcher, cancelable)

    real(s"$name - strictly sequentialize multiple IOs") {
      val length = 1000

      for {
        results <- IO.ref(Vector[Int]())
        gate <- CountDownLatch[IO](length)

        _ <- dispatcher use { runner =>
          IO {
            0.until(length) foreach { i =>
              runner.unsafeRunAndForget(results.update(_ :+ i).guarantee(gate.release))
            }
          } *> gate.await
        }

        vec <- results.get
        _ <- IO(assertEquals(vec, 0.until(length).toVector))
      } yield ()
    }

    ticked(s"$name - reject new tasks after release action is submitted as a task") {
      implicit ticker =>
        val test = dispatcher.allocated.flatMap {
          case (runner, release) =>
            IO(runner.unsafeRunAndForget(release)) *>
              IO.sleep(100.millis) *>
              IO(intercept[IllegalStateException](runner.unsafeRunAndForget(IO(ko))))
        }

        assertCompleteAs(test.void, ())
    }

    real(s"$name - invalidate cancelation action of task when complete") {
      val test = dispatcher use { runner =>
        for {
          latch1 <- IO.deferred[Unit]
          latch2 <- IO.deferred[Unit]
          latch3 <- IO.deferred[Unit]

          pair <- IO(runner.unsafeToFutureCancelable(IO.unit))
          (_, cancel) = pair

          _ <- IO(
            runner.unsafeRunAndForget(latch1.complete(()) *> latch2.get *> latch3.complete(())))

          _ <- latch1.get
          _ <- IO.fromFuture(IO(cancel()))
          _ <- latch2.complete(())

          _ <- latch3.get // this will hang if the test is failing
        } yield ()
      }

      test.parReplicateA_(1000)
    }

    real(s"$name - invalidate cancelation action when racing with task") {
      val test = dispatcher use { runner =>
        IO.ref(false) flatMap { resultR =>
          for {
            latch1 <- IO.deferred[Unit]
            latch2 <- IO.deferred[Unit]

            pair <- IO(runner.unsafeToFutureCancelable(latch1.get))
            (_, cancel) = pair

            _ <- latch1.complete(())
            // the particularly scary case is where the cancel action gets in queue before the next action
            f <- IO(cancel())

            // we're testing to make sure this task runs and isn't canceled
            _ <- IO(runner.unsafeRunAndForget(resultR.set(true) *> latch2.complete(())))
            _ <- IO.fromFuture(IO.pure(f))
            _ <- latch2.get

            b <- resultR.get
          } yield b
        }
      }

      test.flatMap(b => IO(assert(b))).parReplicateA_(1000)
    }
  }

  {
    val D = Dispatcher.parallel[IO](await = true)
    parallel("parallel dispatcher - await = true", D)
    awaitTermination("parallel dispatcher - await = true", D)
  }

  {
    val D = Dispatcher.parallel[IO](await = false)

    parallel("parallel dispatcher - await = false", D)

    real(s"parallel dispatcher - await = false - cancel all inner effects when canceled") {
      for {
        gate1 <- Semaphore[IO](2)
        _ <- gate1.acquireN(2)

        gate2 <- Semaphore[IO](2)
        _ <- gate2.acquireN(2)

        rec = D flatMap { runner =>
          Resource eval {
            IO {
              // these finalizers never return, so this test is intentionally designed to hang
              // they flip their gates first though; this is just testing that both run in parallel
              val a = (gate1.release *> IO.never) onCancel {
                gate2.release *> IO.never
              }

              val b = (gate1.release *> IO.never) onCancel {
                gate2.release *> IO.never
              }

              runner.unsafeRunAndForget(a)
              runner.unsafeRunAndForget(b)
            }
          }
        }

        _ <- rec.use(_ => gate1.acquireN(2)).start

        // if both are not run in parallel, then this will hang
        _ <- gate2.acquireN(2)
      } yield ()
    }
  }

  private def parallel(name: String, dispatcher: Resource[IO, Dispatcher[IO]]) = {

    common(name, dispatcher, true)

    real(s"$name - run multiple IOs in parallel") {
      val num = 10

      for {
        latches <- (0 until num).toList.traverse(_ => Deferred[IO, Unit])
        awaitAll = latches.parTraverse_(_.get)

        // engineer a deadlock: all subjects must be run in parallel or this will hang
        subjects = latches.map(latch => latch.complete(()) >> awaitAll)

        _ <- {
          val rec = dispatcher flatMap { runner =>
            Resource eval {
              subjects.parTraverse_(act => IO(runner.unsafeRunAndForget(act)))
            }
          }

          rec.use(_ => awaitAll)
        }
      } yield ()
    }

    real(s"$name - run many IOs simultaneously to full completion") {
      val length = 256 // 10000 times out on my machine

      for {
        results <- IO.ref(Vector[Int]())
        gate <- CountDownLatch[IO](length)

        _ <- dispatcher use { runner =>
          IO {
            0.until(length) foreach { i =>
              runner.unsafeRunAndForget(results.update(_ :+ i).guarantee(gate.release))
            }
          } *> gate.await
        }

        vec <- results.get
        _ <- IO(assertEquals(vec.sorted, 0.until(length).toVector))
      } yield ()
    }

    // https://github.com/typelevel/cats-effect/issues/3898
    real(s"$name - not hang when cancelling") {
      val test = dispatcher.use { dispatcher =>
        val action = IO.fromFuture {
          IO {
            val (_, cancel) = dispatcher.unsafeToFutureCancelable(IO.never)
            cancel()
          }
        }

        action.replicateA_(if (isJVM) 1000 else 1)
      }

      if (isJVM)
        test.parReplicateA_(100)
      else
        test
    }

    real(s"$name - cancelation does not block a worker") {
      TestControl executeEmbed {
        dispatcher use { runner =>
          (IO.deferred[Unit], IO.deferred[Unit]) flatMapN { (latch1, latch2) =>
            val task = (latch1.complete(()) *> latch2.get).uncancelable

            IO(runner.unsafeToFutureCancelable(task)._2) flatMap { cancel =>
              latch1.get *>
                IO(cancel()) *>
                IO(runner.unsafeRunAndForget(latch2.complete(()))) *>
                latch2.get
            }
          }
        }
      }
    }

    real(s"$name - cancelation race does not block a worker") {
      dispatcher
        .use { runner =>
          IO.deferred[Unit] flatMap { latch =>
            val clogUp = IO {
              val task = latch.get.uncancelable
              runner.unsafeToFutureCancelable(task)._2
            }.flatMap { cancel =>
              // cancel concurrently
              // We want to trigger race condition where task starts but then discovers it was canceled
              IO(cancel())
            }

            clogUp.parReplicateA_(1000) *>
              // now try to run a new task
              IO.fromFuture(IO(runner.unsafeToFuture(latch.complete(()))))
          }
        }
        .replicateA_(if (isJVM) 500 else 1)
    }
  }

  private def common(
      name: String,
      dispatcher: Resource[IO, Dispatcher[IO]],
      cancelable: Boolean) = {

    real(s"$name - run a synchronous IO") {
      val ioa = IO(1).map(_ + 2)
      val rec =
        dispatcher.flatMap(runner =>
          Resource.eval(IO.fromFuture(IO(runner.unsafeToFuture(ioa)))))
      rec.use(i => IO(assertEquals(i, 3)))
    }

    real(s"$name - run an asynchronous IO") {
      val ioa = (IO(1) <* IO.cede).map(_ + 2)
      val rec =
        dispatcher.flatMap(runner =>
          Resource.eval(IO.fromFuture(IO(runner.unsafeToFuture(ioa)))))
      rec.use(i => IO(assertEquals(i, 3)))
    }

    real(s"$name - run several IOs back to back") {
      val counter = new AtomicInteger(0)
      val increment = IO(counter.getAndIncrement()).void

      val num = 10

      val rec = dispatcher flatMap { runner =>
        Resource.eval(IO.fromFuture(IO(runner.unsafeToFuture(increment))).replicateA(num).void)
      }

      rec.use(_ => IO(assertEquals(counter.get(), num)))
    }

    real(s"$name - raise an error on leaked runner") {
      dispatcher.use(IO.pure(_)) flatMap { runner =>
        IO {
          intercept[IllegalStateException](runner.unsafeRunAndForget(IO(ko)))
        }
      }
    }

    real(s"$name - report exception if raised during unsafeRunAndForget") {
      def ec2(ec1: ExecutionContext, er: Promise[Boolean]) = new ExecutionContext {
        def reportFailure(t: Throwable) = er.success(true)
        def execute(r: Runnable) = ec1.execute(r)
      }

      val test = for {
        ec <- Resource.eval(IO.executionContext)
        errorReporter <- Resource.eval(IO(Promise[Boolean]()))
        customEc = ec2(ec, errorReporter)
        _ <- dispatcher
          .evalOn(customEc)
          .flatMap(runner =>
            Resource.eval(IO(runner.unsafeRunAndForget(IO.raiseError(new Exception("boom"))))))
      } yield errorReporter

      test
        .use(t =>
          IO.fromFutureCancelable(IO((t.future, IO.unit))).timeoutTo(1.second, IO.pure(false)))
        .flatMap(t => IO(assertEquals(t, true)))
    }

    real(s"$name - do not treat exception in unsafeRunToFuture as unhandled") {
      import scala.concurrent.TimeoutException
      def ec2(ec1: ExecutionContext, er: Promise[Boolean]) = new ExecutionContext {
        def reportFailure(t: Throwable) = er.failure(t)
        def execute(r: Runnable) = ec1.execute(r)
      }

      val test = for {
        ec <- Resource.eval(IO.executionContext)
        errorReporter <- Resource.eval(IO(Promise[Boolean]()))
        customEc = ec2(ec, errorReporter)
        _ <- dispatcher
          .evalOn(customEc)
          .flatMap(runner =>
            Resource.eval(IO(runner.unsafeToFuture(IO.raiseError(new Exception("boom"))))))
      } yield errorReporter

      test.use(t =>
        IO.fromFutureCancelable(IO((t.future, IO.unit)))
          .timeout(1.second)
          .mustFailWith[TimeoutException])
    }

    real(s"$name - respect self-cancelation") {
      dispatcher use { runner =>
        for {
          resultR <- IO.ref(false)
          latch <- IO.deferred[Unit]

          // if the inner action is erroneously masked, `resultR` will be flipped because cancelation will be suppressed
          _ <- IO(
            runner.unsafeRunAndForget(
              (IO.canceled >> resultR.set(true)).guarantee(latch.complete(()).void)))
          _ <- latch.get

          result <- resultR.get
          _ <- IO(assert(!result))

          secondLatch <- IO.deferred[Unit]
          _ <- IO(runner.unsafeRunAndForget(secondLatch.complete(()).void))
          _ <- secondLatch.get // if the dispatcher itself is dead, this will hang
        } yield ()
      }
    }

    real(s"$name - reject new tasks while shutting down") {
      val test = (IO.deferred[Unit], IO.deferred[Unit]) flatMapN { (latch1, latch2) =>
        dispatcher.allocated flatMap {
          case (runner, release) =>
            for {
              _ <- IO(
                runner.unsafeRunAndForget(IO.unit.guarantee(latch1.complete(()) >> latch2.get)))
              _ <- latch1.get

              challenge = IO(runner.unsafeRunAndForget(IO.unit))
                .delayBy(500.millis) // gross sleep to make sure we're actually in the release
                .guarantee(latch2.complete(()).void)

              _ <- release &> challenge
            } yield ko
        }
      }

      test.attempt.flatMap(r => IO(assert(r.isLeft))).parReplicateA_(50)
    }

    ticked(s"$name - cancel inner awaits when canceled") { implicit ticker =>
      val work = dispatcher.useForever
      val test = work.background.use(_ => IO.sleep(100.millis))

      assertCompleteAs(test, ())
    }

    if (!cancelable) {
      real(s"$name - ignore action cancelation") {
        var canceled = false

        val rec = dispatcher flatMap { runner =>
          val run = IO {
            runner
              .unsafeToFutureCancelable(IO.sleep(500.millis).onCancel(IO { canceled = true }))
              ._2
          }

          Resource eval {
            run.flatMap(ct => IO.sleep(200.millis) >> IO.fromFuture(IO(ct())))
          }
        }

        TestControl.executeEmbed(rec.use(_ => IO(assert(!canceled))))
      }
    } else {
      real(s"$name - forward cancelation onto the inner action") {
        val test = dispatcher use { runner =>
          IO.ref(false) flatMap { resultsR =>
            val action = IO.never.onCancel(resultsR.set(true))
            IO(runner.unsafeToFutureCancelable(action)) flatMap {
              case (_, cancel) =>
                IO.sleep(500.millis) *> IO.fromFuture(IO(cancel())) *> resultsR.get
            }
          }
        }

        TestControl.executeEmbed(test).flatMap(b => IO(assert(b)))
      }

      real(s"$name - support multiple concurrent cancelations") {
        dispatcher use { runner =>
          val count = new AtomicInteger(0)

          for {
            latch0 <- IO.deferred[Unit]
            latch1 <- IO.deferred[Unit]
            latch2 <- IO.deferred[Unit]

            action = (latch0.complete(()) *> IO.never)
              .onCancel(latch1.complete(()) *> latch2.get)

            pair <- IO(runner.unsafeToFutureCancelable(action))
            (_, cancel) = pair

            _ <- latch0.get

            ec <- IO.executionContext
            cancelAction = IO(cancel().onComplete(_ => count.getAndIncrement())(ec))
            _ <- cancelAction
            _ <- cancelAction
            _ <- cancelAction

            _ <- latch1.get
            _ <- IO.sleep(100.millis)
            _ <- IO(assertEquals(count.get(), 0))

            _ <- latch2.complete(())
            _ <- IO.sleep(100.millis)
            _ <- IO(assertEquals(count.get(), 3))
          } yield ()
        }
      }

      real("complete / cancel race") {
        val tsk = dispatcher.use { dispatcher =>
          IO.fromFuture(IO {
            val (_, cancel) = dispatcher.unsafeToFutureCancelable(IO.unit)
            val cancelFut = cancel()
            cancelFut
          })
        }

        tsk.replicateA_(if (isJVM) 10000 else 1)
      }
    }
  }

  private def awaitTermination(name: String, dispatcher: Resource[IO, Dispatcher[IO]]) = {

    real(s"$name - wait for the completion of the active fibers") {
      def makeRunner(releaseInner: CountDownLatch[IO]) =
        for {
          runner <- dispatcher
          _ <- Resource.make(IO.unit)(_ => releaseInner.release)
        } yield runner

      for {
        releaseInner <- CountDownLatch[IO](1)
        fiberLatch <- CountDownLatch[IO](1)

        fiber <- makeRunner(releaseInner).use { runner =>
          for {
            cdl <- CountDownLatch[IO](1)
            _ <- IO(runner.unsafeRunAndForget(cdl.release >> fiberLatch.await))
            _ <- cdl.await // make sure the execution of fiber has started
          } yield ()
        }.start
        _ <- releaseInner.await // release process has started
        released1 <- fiber.join.as(true).timeoutTo(200.millis, IO(false))
        _ <- fiberLatch.release
        released2 <- fiber.join.as(true).timeoutTo(200.millis, IO(false))
      } yield {
        assert(!released1)
        assert(released2)
      }
    }

    ticked(s"$name - issue #3506: await unsafeRunAndForget") { implicit ticker =>
      val result = for {
        latch <- IO.deferred[Unit]

        repro = (latch.complete(()) >> IO.never).uncancelable
        _ <- dispatcher.use(runner => IO(runner.unsafeRunAndForget(repro)) >> latch.get)
      } yield ()

      assertNonTerminate(result)
    }

    real(s"$name - cancel active fibers when an error is produced") {
      case object TestException extends RuntimeException

      IO.deferred[Unit] flatMap { canceled =>
        IO.deferred[Unit] flatMap { gate =>
          val test = dispatcher use { runner =>
            for {
              _ <- IO(
                runner.unsafeRunAndForget(
                  (gate.complete(()) >> IO.never).onCancel(canceled.complete(()).void)))
              _ <- gate.get
              _ <- IO.raiseError(TestException).void
            } yield ()
          }

          test.handleError(_ => ()) >> canceled.get
        }
      }
    }
  }

  private def ko(implicit loc: munit.Location) = fail("fail")
}
