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

import cats.syntax.all._

import scala.concurrent.duration._

class SupervisorSuite extends BaseSuite with DetectPlatform {

  supervisorTests("concurrent", Supervisor.applyForConcurrent)
  supervisorTests("async", Supervisor.applyForAsync)

  private def supervisorTests(
      name: String,
      constructor: (Boolean, Option[Outcome[IO, Throwable, ?] => Boolean]) => Resource[
        IO,
        Supervisor[IO]]) = {

    ticked(s"$name - start a fiber that completes successfully") { implicit ticker =>
      val test = constructor(false, None).use { supervisor =>
        supervisor.supervise(IO(1)).flatMap(_.join)
      }

      assertCompleteAs(test, Outcome.succeeded[IO, Throwable, Int](IO.pure(1)))
    }

    ticked(s"$name - start a fiber that raises an error") { implicit ticker =>
      val t = new Throwable("failed")
      val test = constructor(false, None).use { supervisor =>
        supervisor.supervise(IO.raiseError[Unit](t)).flatMap(_.join)
      }

      assertCompleteAs(test, Outcome.errored[IO, Throwable, Unit](t))
    }

    ticked(s"$name - start a fiber that self-cancels") { implicit ticker =>
      val test = constructor(false, None).use { supervisor =>
        supervisor.supervise(IO.canceled).flatMap(_.join)
      }

      assertCompleteAs(test, Outcome.canceled[IO, Throwable, Unit])
    }

    ticked(s"$name - cancel active fibers when supervisor exits") { implicit ticker =>
      val test = for {
        fiber <- constructor(false, None).use { supervisor =>
          supervisor.supervise(IO.never[Unit])
        }
        outcome <- fiber.join
      } yield outcome

      assertCompleteAs(test, Outcome.canceled[IO, Throwable, Unit])
    }

    ticked(s"$name - await active fibers when supervisor exits with await = true") {
      implicit ticker =>
        val test = constructor(true, None).use { supervisor =>
          supervisor.supervise(IO.never[Unit]).void
        }

        assertNonTerminate(test)
    }

    ticked(
      s"$name - await active fibers when supervisor with restarter exits with await = true") {
      implicit ticker =>
        val test = constructor(true, Some(_ => true)) use { supervisor =>
          supervisor.supervise(IO.never[Unit]).void
        }

        assertNonTerminate(test)
    }

    ticked(
      s"$name - await active fibers through a fiber when supervisor with restarter exits with await = true") {
      implicit ticker =>
        val test = constructor(true, Some(_ => true)) use { supervisor =>
          supervisor.supervise(IO.never[Unit]).void
        }

        assertNonTerminate(test.start.flatMap(_.join).void)
    }

    ticked(s"$name - stop restarting fibers when supervisor exits with await = true") {
      implicit ticker =>
        val test = for {
          counter <- IO.ref(0)
          signal <- Semaphore[IO](1)
          done <- IO.deferred[Unit]

          fiber <- constructor(true, Some(_ => true)).use { supervisor =>
            for {
              _ <- signal.acquire
              _ <- supervisor.supervise(signal.acquire >> counter.update(_ + 1))

              _ <- IO.sleep(1.millis)
              _ <- signal.release
              _ <- IO.sleep(1.millis)
              _ <- signal.release
              _ <- IO.sleep(1.millis)

              _ <- done.complete(())
            } yield ()
          }.start

          _ <- done.get
          completed1 <- fiber.join.as(true).timeoutTo(200.millis, IO.pure(false))
          _ <- IO(assert(!completed1))

          _ <- signal.release
          completed2 <- fiber.join.as(true).timeoutTo(200.millis, IO.pure(false))
          _ <- IO(assert(completed2))

          count <- counter.get
          _ <- IO(assertEquals(count, 3))
        } yield ()

        assertCompleteAs(test, ())
    }

    ticked(s"$name - cancel awaited fibers when exiting with error") { implicit ticker =>
      case object TestException extends RuntimeException

      val test = IO.deferred[Unit] flatMap { latch =>
        IO.deferred[Unit] flatMap { canceled =>
          val supervision = constructor(true, None) use { supervisor =>
            val action = (latch.complete(()) >> IO.never).onCancel(canceled.complete(()).void)
            supervisor.supervise(action) >> latch.get >> IO.raiseError(TestException)
          }

          supervision.guarantee(canceled.get)
        }
      }

      assertFailAs(test, TestException)
    }

    ticked(s"$name - cancel awaited fibers when canceled") { implicit ticker =>
      val test = IO.deferred[Unit] flatMap { latch =>
        IO.deferred[Unit] flatMap { canceled =>
          val supervision = constructor(true, None) use { supervisor =>
            val action = (latch.complete(()) >> IO.never).onCancel(canceled.complete(()).void)
            supervisor.supervise(action) >> latch.get >> IO.canceled
          }

          supervision.guarantee(canceled.get)
        }
      }

      assertSelfCancel(test)
    }

    ticked(s"$name - check restart a fiber if it produces an error") { implicit ticker =>
      case object TestException extends RuntimeException {
        override def printStackTrace(): Unit =
          () // this is an orphan error; we suppress the printing
      }

      val test = IO.ref(true) flatMap { raiseR =>
        IO.ref(0) flatMap { counterR =>
          val flipRaise = raiseR.set(false) >> IO.raiseError(TestException)
          val action = (counterR.update(_ + 1) >> raiseR.get).ifM(flipRaise, IO.pure(42))

          constructor(true, Some(_.fold(false, _ => true, _ => false))).use { supervisor =>
            supervisor.supervise(action).flatMap(_.joinWithNever)
          } <* counterR.get.flatMap(count => IO(assertEquals(count, 2)))
        }
      }

      assertCompleteAs(test, 42)
    }

    ticked(s"$name - check restart a fiber if it cancels") { implicit ticker =>
      val test = IO.ref(true) flatMap { raiseR =>
        IO.ref(0) flatMap { counterR =>
          val flipCancel = raiseR.set(false) >> IO.canceled.as(1)
          val action = (counterR.update(_ + 1) >> raiseR.get).ifM(flipCancel, IO.pure(42))

          constructor(true, Some(_.fold(true, _ => false, _ => false))).use { supervisor =>
            supervisor.supervise(action).flatMap(_.joinWithNever)
          } <* counterR.get.flatMap(count => IO(assertEquals(count, 2)))
        }
      }

      assertCompleteAs(test, 42)
    }

    real(s"$name - cancel inner fiber and ignore restart if outer canceled") {
      val test = IO.deferred[Unit] flatMap { latch =>
        constructor(true, Some(_.fold(true, _ => false, _ => false))).use { supervisor =>
          supervisor.supervise(latch.complete(()) >> IO.canceled) >> latch.get >> IO.canceled
        }
      }

      // if this doesn't work properly, the test will hang
      test.start.flatMap(_.join).timeoutTo(4.seconds, IO(sys.error("err"))).void
    }

    real(s"$name - cancel inner fiber and ignore restart if outer errored") {
      case object TestException extends RuntimeException

      val test = IO.deferred[Unit] flatMap { latch =>
        constructor(true, Some(_.fold(true, _ => false, _ => false))).use { supervisor =>
          supervisor.supervise(latch.complete(()) >> IO.canceled) >> latch.get >> IO.raiseError(
            TestException)
        }
      }

      // if this doesn't work properly, the test will hang
      test.start.flatMap(_.join).timeoutTo(4.seconds, IO(sys.error("err"))).void
    }

    real(s"$name - supervise / finalize race") {
      superviseFinalizeRace(constructor(false, None), IO.never[Unit])
    }

    real(s"$name - supervise / finalize race with checkRestart") {
      superviseFinalizeRace(constructor(false, Some(_ => true)), IO.canceled)
    }

    def superviseFinalizeRace(mkSupervisor: Resource[IO, Supervisor[IO]], task: IO[Unit]) = {
      val tsk = IO.uncancelable { poll =>
        mkSupervisor.allocated.flatMap {
          case (supervisor, close) =>
            supervisor.supervise(IO.never[Unit]).replicateA(100).flatMap { fibers =>
              val tryFork = supervisor.supervise(task).map(Some(_)).recover {
                case ex: IllegalStateException =>
                  assertEquals(ex.getMessage, "supervisor already shutdown")
                  None
              }
              IO.both(tryFork, close).flatMap {
                case (maybeFiber, _) =>
                  def joinAndCheck(fib: Fiber[IO, Throwable, Unit]) =
                    fib.join.flatMap { oc => IO(assert(oc.isCanceled)) }
                  poll(fibers.traverse(joinAndCheck) *> {
                    maybeFiber match {
                      case None =>
                        IO.unit
                      case Some(fiber) =>
                        // `supervise` won the race, so our fiber must've been cancelled:
                        joinAndCheck(fiber)
                    }
                  })
              }
            }
        }
      }
      tsk.parReplicateA_(if (isJVM) 700 else 1)
    }

    real(s"$name - submit to closed supervisor") {
      constructor(false, None).use(IO.pure(_)).flatMap { leaked =>
        leaked.supervise(IO.unit).attempt.flatMap { r =>
          IO {
            r match {
              case Left(e) => assert(e.isInstanceOf[IllegalStateException])
              case Right(v) => fail(s"Expected Left, got $v")
            }
          }
        }
      }
    }

    real(s"$name - cancel beats restart") {
      val test = for {
        count <- IO.ref(0)
        firstStarted <- IO.deferred[Unit]
        cancelReachedChild <- IO.deferred[Unit]
        releaseCancel <- IO.deferred[Unit]
        _ <- constructor(false, Some(_ => true)).use { supervisor =>
          val action =
            (count.update(_ + 1) *> firstStarted.complete(()) *> IO.never[Unit]).onCancel(
              cancelReachedChild.complete(()) *> IO.uncancelable(_ => releaseCancel.get))

          for {
            adapted <- supervisor.supervise(action)
            _ <- firstStarted.get
            cancel <- adapted.cancel.start
            _ <- cancelReachedChild.get
            _ <- releaseCancel.complete(())
            _ <- cancel.join
            _ <- adapted.join
          } yield ()
        }
        result <- count.get
        _ <- IO(assertEquals(result, 1))
      } yield ()

      test
    }

    real(s"$name - restart beats cancel") {
      val test = for {
        count <- IO.ref(0)
        restarted <- IO.deferred[Unit]
        _ <- constructor(false, Some(_ => true)).use { supervisor =>
          val action = count.updateAndGet(_ + 1).flatMap {
            case 1 => IO.canceled
            case 2 => restarted.complete(()) *> IO.never[Unit]
            case n => IO.raiseError(new AssertionError(s"unexpected invocation: $n"))
          }

          for {
            adapted <- supervisor.supervise(action)
            _ <- restarted.get
            _ <- adapted.cancel
            _ <- adapted.join
          } yield ()
        }
        result <- count.get
        _ <- IO(assertEquals(result, 2))
      } yield ()

      test
    }

    def superviseCancelRace(mkSupervisor: Resource[IO, Supervisor[IO]]) = {
      val N = if (isJVM) 1000 else 5
      val M = if (isJVM) 20 else 2
      val tsk = mkSupervisor.use { supervisor =>
        supervisor
          .supervise(IO.unit)
          .flatMap(_.cancel)
          .replicateA_(N)
          .parReplicateA_(M)
          .flatMap { _ =>
            // let's wait a bit (for cleanup to happen):
            IO.sleep(0.2.second) *> {
              val st = supervisor.asInstanceOf[Supervisor.SupervisorImpl[IO]].state
              // the supervised fibers must've been cleaned up from the internal state:
              st.numberOfFibers.flatMap { numFibs => IO(assertEquals(numFibs, 0)) }
            }
          }
      }
      tsk
    }

    real(s"$name - supervise / cancel race cleanup") {
      superviseCancelRace(constructor(false, None))
    }

    real(s"$name - supervise / cancel race cleanup (with restart)") {
      superviseCancelRace(constructor(false, Some(_ => true)))
    }
  }
}
