/*
 * Copyright 2020-2023 Typelevel
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

class DispatcherSpec extends BaseSpec with DetectPlatform {

  override def executionTimeout = 30.seconds

  "sequential dispatcher" should {
    "await = true" >> {
      val D = Dispatcher.sequential[IO](await = true)

      sequential(D)

      awaitTermination(D)

      "not hang" in real {
        D.use(dispatcher => IO(dispatcher.unsafeRunAndForget(IO.unit)))
          .replicateA(if (isJS || isNative) 1 else 10000)
          .as(true)
      }
    }

    "await = false" >> {
      val D = Dispatcher.sequential[IO](await = false)

      sequential(D)

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

  private def sequential(dispatcher: Resource[IO, Dispatcher[IO]]) = {

    common(dispatcher)

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

    common(dispatcher)

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

          rec.use(_ => IO.unit)
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

    "forward cancelation onto the inner action" in real {
      var canceled = false

      val rec = dispatcher flatMap { runner =>
        val run = IO {
          runner.unsafeToFutureCancelable(IO.never.onCancel(IO { canceled = true }))._2
        }

        Resource eval {
          run.flatMap(ct => IO.sleep(500.millis) >> IO.fromFuture(IO(ct())))
        }
      }

      TestControl.executeEmbed(rec.use(_ => IO(canceled must beTrue)))
    }
  }

  private def common(dispatcher: Resource[IO, Dispatcher[IO]]) = {

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
      @volatile
      var counter = 0
      val increment = IO(counter += 1)

      val num = 10

      val rec = dispatcher flatMap { runner =>
        Resource.eval(IO.fromFuture(IO(runner.unsafeToFuture(increment))).replicateA(num).void)
      }

      rec.use(_ => IO(counter mustEqual num))
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
      (IO.ref(false), IO.ref(false))
        .flatMapN { (resultR, rogueResultR) =>
          dispatcher
            .allocated
            .flatMap {
              case (runner, release) =>
                IO(runner.unsafeRunAndForget(
                  IO.sleep(1.second).uncancelable.guarantee(resultR.set(true)))) *>
                  IO.sleep(100.millis) *>
                  release.both(
                    IO.sleep(500.nanos) *>
                      IO(runner.unsafeRunAndForget(rogueResultR.set(true))).attempt
                  )
            }
            .flatMap {
              case (_, rogueSubmitResult) =>
                for {
                  result <- resultR.get
                  rogueResult <- rogueResultR.get
                  _ <- IO(result must beTrue)
                  _ <- IO(if (rogueResult == false) {
                    // if the rogue task is not completed then we must have failed to submit it
                    rogueSubmitResult must beLeft
                  })
                } yield ok
            }
        }
        .replicateA(5)
    }

    "issue 3501: reject new tasks after release action is submitted as a task" in ticked {
      implicit ticker =>
        val test = dispatcher.allocated.flatMap {
          case (runner, release) =>
            IO(runner.unsafeRunAndForget(IO.sleep(50.millis) *> release)) *>
              IO.sleep(100.millis) *>
              IO(runner.unsafeRunAndForget(IO(ko)) must throwAn[IllegalStateException])
        }

        test.void must completeAs(())
    }

    "cancel inner awaits when canceled" in ticked { implicit ticker =>
      val work = dispatcher.useForever
      val test = work.background.use(_ => IO.sleep(100.millis))

      test must completeAs(())
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
        resultR <- IO.ref(false)
        _ <- dispatcher.use { runner => IO(runner.unsafeRunAndForget(resultR.set(true))) }
        result <- resultR.get
      } yield result
      result must completeAs(true)
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
