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
package std

import cats.effect.kernel.Deferred
import cats.effect.testkit.TestControl
import cats.syntax.all._

import scala.concurrent.{ExecutionContext, Promise}
import scala.concurrent.duration._

import java.util.concurrent.atomic.AtomicInteger

class DispatcherSpec extends BaseSpec with DetectPlatform {

  override def executionTimeout = 30.seconds

  "sequential dispatcher (cancelable = false)" should {
    "await = true" >> {
      val D = Dispatcher.sequential[IO](await = true)

      sequential(D, false)

      awaitTermination(D)

      "not hang" in real {
        D.use(dispatcher => IO(dispatcher.unsafeRunAndForget(IO.unit)))
          .replicateA(if (isJS || isNative) 1 else 10000)
          .as(true)
      }

      "await work queue drain on shutdown" in real {
        val count = 1000

        IO.ref(0) flatMap { resultsR =>
          val increments = D use { runner =>
            IO {
              0.until(count).foreach(_ => runner.unsafeRunAndForget(resultsR.update(_ + 1)))
            }
          }

          increments *> resultsR.get.flatMap(r => IO(r mustEqual count))
        }
      }

      "terminating worker preserves task order" in real {
        val count = 10

        IO.ref(Vector[Int]()) flatMap { resultsR =>
          val appends = D use { runner =>
            IO {
              0.until(count).foreach(i => runner.unsafeRunAndForget(resultsR.update(_ :+ i)))
            }
          }

          appends *> resultsR.get.flatMap(r => IO(r mustEqual 0.until(count).toVector))
        }
      }

      "correctly backpressure cancellation" in real {
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
                (ctr1.get, ctr2.get).flatMapN { (v1, v2) => IO(v1 mustEqual v2) }
              }
            }
          }
        }.replicateA_(if (isJVM) 10000 else 1)
          .as(ok)
      }
    }

    "await = false" >> {
      val D = Dispatcher.sequential[IO](await = false)

      sequential(D, false)

      "cancel all inner effects when canceled" in real {
        var canceled = false

        val body = D use { runner =>
          IO(runner.unsafeRunAndForget(IO.never.onCancel(IO { canceled = true }))) *> IO.never
        }

        val action = body.start.flatMap(f => IO.sleep(500.millis) *> f.cancel)

        TestControl.executeEmbed(action *> IO(canceled must beTrue))
      }
    }
  }

  "sequential dispatcher (cancelable = true)" should {
    "await = true" >> {
      val D = Dispatcher.sequentialCancelable[IO](await = true)

      sequential(D, true)

      awaitTermination(D)

      "not hang" in real {
        D.use(dispatcher => IO(dispatcher.unsafeRunAndForget(IO.unit)))
          .replicateA(if (isJS || isNative) 1 else 10000)
          .as(true)
      }
    }

    "await = false" >> {
      val D = Dispatcher.sequentialCancelable[IO](await = false)

      sequential(D, true)

      "cancel all inner effects when canceled" in real {
        var canceled = false

        val body = D use { runner =>
          IO(runner.unsafeRunAndForget(IO.never.onCancel(IO { canceled = true }))) *> IO.never
        }

        val action = body.start.flatMap(f => IO.sleep(500.millis) *> f.cancel)

        TestControl.executeEmbed(action *> IO(canceled must beTrue))
      }
    }
  }

  private def sequential(dispatcher: Resource[IO, Dispatcher[IO]], cancelable: Boolean) = {

    common(dispatcher, cancelable)

    "strictly sequentialize multiple IOs" in real {
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
        _ <- IO(vec mustEqual 0.until(length).toVector)
      } yield ok
    }

    "reject new tasks after release action is submitted as a task" in ticked {
      implicit ticker =>
        val test = dispatcher.allocated.flatMap {
          case (runner, release) =>
            IO(runner.unsafeRunAndForget(release)) *>
              IO.sleep(100.millis) *>
              IO(runner.unsafeRunAndForget(IO(ko)) must throwAn[IllegalStateException])
        }

        test.void must completeAs(())
    }

    "invalidate cancelation action of task when complete" in real {
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
        } yield ok
      }

      test.parReplicateA_(1000).as(ok)
    }

    "invalidate cancelation action when racing with task" in real {
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

      test.flatMap(b => IO(b must beTrue)).parReplicateA_(1000).as(ok)
    }
  }

  "parallel dispatcher" should {
    "await = true" >> {
      val D = Dispatcher.parallel[IO](await = true)

      parallel(D)

      awaitTermination(D)
    }

    "await = false" >> {
      val D = Dispatcher.parallel[IO](await = false)

      parallel(D)

      "cancel all inner effects when canceled" in real {
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
        } yield ok
      }
    }
  }

  private def parallel(dispatcher: Resource[IO, Dispatcher[IO]]) = {

    common(dispatcher, true)

    "run multiple IOs in parallel" in real {
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
      } yield ok
    }

    "run many IOs simultaneously to full completion" in real {
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
        _ <- IO(vec must containAllOf(0.until(length).toVector))
      } yield ok
    }

    // https://github.com/typelevel/cats-effect/issues/3898
    "not hang when cancelling" in real {
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
        test.parReplicateA_(100).as(ok)
      else
        test.as(ok)
    }

    "cancelation does not block a worker" in real {
      TestControl executeEmbed {
        dispatcher use { runner =>
          (IO.deferred[Unit], IO.deferred[Unit]) flatMapN { (latch1, latch2) =>
            val task = (latch1.complete(()) *> latch2.get).uncancelable

            IO(runner.unsafeToFutureCancelable(task)._2) flatMap { cancel =>
              latch1.get *>
                IO(cancel()) *>
                IO(runner.unsafeRunAndForget(latch2.complete(()))) *>
                latch2.get.as(ok)
            }
          }
        }
      }
    }

    "cancelation race does not block a worker" in real {
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
        .as(ok)
    }
  }

  private def common(dispatcher: Resource[IO, Dispatcher[IO]], cancelable: Boolean) = {

    "run a synchronous IO" in real {
      val ioa = IO(1).map(_ + 2)
      val rec =
        dispatcher.flatMap(runner =>
          Resource.eval(IO.fromFuture(IO(runner.unsafeToFuture(ioa)))))
      rec.use(i => IO(i mustEqual 3))
    }

    "run an asynchronous IO" in real {
      val ioa = (IO(1) <* IO.cede).map(_ + 2)
      val rec =
        dispatcher.flatMap(runner =>
          Resource.eval(IO.fromFuture(IO(runner.unsafeToFuture(ioa)))))
      rec.use(i => IO(i mustEqual 3))
    }

    "run several IOs back to back" in real {
      val counter = new AtomicInteger(0)
      val increment = IO(counter.getAndIncrement()).void

      val num = 10

      val rec = dispatcher flatMap { runner =>
        Resource.eval(IO.fromFuture(IO(runner.unsafeToFuture(increment))).replicateA(num).void)
      }

      rec.use(_ => IO(counter.get() mustEqual num))
    }

    "raise an error on leaked runner" in real {
      dispatcher.use(IO.pure(_)) flatMap { runner =>
        IO {
          runner.unsafeRunAndForget(IO(ko)) must throwAn[IllegalStateException]
        }
      }
    }

    "report exception if raised during unsafeRunAndForget" in real {
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
        .flatMap(t => IO(t mustEqual true))
    }

    "do not treat exception in unsafeRunToFuture as unhandled" in real {
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

    "respect self-cancelation" in real {
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
          _ <- IO(result must beFalse)

          secondLatch <- IO.deferred[Unit]
          _ <- IO(runner.unsafeRunAndForget(secondLatch.complete(()).void))
          _ <- secondLatch.get // if the dispatcher itself is dead, this will hang
        } yield ok
      }
    }

    "reject new tasks while shutting down" in real {
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

      test.attempt.flatMap(r => IO(r must beLeft)).parReplicateA_(50).as(ok)
    }

    "cancel inner awaits when canceled" in ticked { implicit ticker =>
      val work = dispatcher.useForever
      val test = work.background.use(_ => IO.sleep(100.millis))

      test must completeAs(())
    }

    if (!cancelable) {
      "ignore action cancelation" in real {
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

        TestControl.executeEmbed(rec.use(_ => IO(canceled must beFalse)))
      }
    } else {
      "forward cancelation onto the inner action" in real {
        val test = dispatcher use { runner =>
          IO.ref(false) flatMap { resultsR =>
            val action = IO.never.onCancel(resultsR.set(true))
            IO(runner.unsafeToFutureCancelable(action)) flatMap {
              case (_, cancel) =>
                IO.sleep(500.millis) *> IO.fromFuture(IO(cancel())) *> resultsR.get
            }
          }
        }

        TestControl.executeEmbed(test).flatMap(b => IO(b must beTrue))
      }

      "support multiple concurrent cancelations" in real {
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
            _ <- IO(count.get() mustEqual 0)

            _ <- latch2.complete(())
            _ <- IO.sleep(100.millis)
            _ <- IO(count.get() mustEqual 3)
          } yield ok
        }
      }

      "complete / cancel race" in real {
        val tsk = dispatcher.use { dispatcher =>
          IO.fromFuture(IO {
            val (_, cancel) = dispatcher.unsafeToFutureCancelable(IO.unit)
            val cancelFut = cancel()
            cancelFut
          })
        }

        tsk.replicateA_(if (isJVM) 10000 else 1).as(ok)
      }
    }
  }

  private def awaitTermination(dispatcher: Resource[IO, Dispatcher[IO]]) = {

    "wait for the completion of the active fibers" in real {
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
        released1 must beFalse
        released2 must beTrue
      }
    }

    "issue #3506: await unsafeRunAndForget" in ticked { implicit ticker =>
      val result = for {
        latch <- IO.deferred[Unit]

        repro = (latch.complete(()) >> IO.never).uncancelable
        _ <- dispatcher.use(runner => IO(runner.unsafeRunAndForget(repro)) >> latch.get)
      } yield ()

      result must nonTerminate
    }

    "cancel active fibers when an error is produced" in real {
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

          test.handleError(_ => ()) >> canceled.get.as(ok)
        }
      }
    }
  }
}
