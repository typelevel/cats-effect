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

import org.specs2.specification.core.Fragments

import scala.concurrent.duration._

class SupervisorSpec extends BaseSpec {

  "Supervisor" should {
    "concurrent" >> {
      supervisorTests(Supervisor.applyForConcurrent)
    }

    "async" >> {
      supervisorTests(Supervisor.applyForAsync)
    }
  }

  private def supervisorTests(
      constructor: (
          Boolean,
          Option[Outcome[IO, Throwable, _] => Boolean]) => Resource[IO, Supervisor[IO]])
      : Fragments = {

    "start a fiber that completes successfully" in ticked { implicit ticker =>
      val test = constructor(false, None).use { supervisor =>
        supervisor.supervise(IO(1)).flatMap(_.join)
      }

      test must completeAs(Outcome.succeeded[IO, Throwable, Int](IO.pure(1)))
    }

    "start a fiber that raises an error" in ticked { implicit ticker =>
      val t = new Throwable("failed")
      val test = constructor(false, None).use { supervisor =>
        supervisor.supervise(IO.raiseError[Unit](t)).flatMap(_.join)
      }

      test must completeAs(Outcome.errored[IO, Throwable, Unit](t))
    }

    "start a fiber that self-cancels" in ticked { implicit ticker =>
      val test = constructor(false, None).use { supervisor =>
        supervisor.supervise(IO.canceled).flatMap(_.join)
      }

      test must completeAs(Outcome.canceled[IO, Throwable, Unit])
    }

    "cancel active fibers when supervisor exits" in ticked { implicit ticker =>
      val test = for {
        fiber <- constructor(false, None).use { supervisor =>
          supervisor.supervise(IO.never[Unit])
        }
        outcome <- fiber.join
      } yield outcome

      test must completeAs(Outcome.canceled[IO, Throwable, Unit])
    }

    "await active fibers when supervisor exits with await = true" in ticked { implicit ticker =>
      val test = constructor(true, None).use { supervisor =>
        supervisor.supervise(IO.never[Unit]).void
      }

      test must nonTerminate
    }

    "await active fibers when supervisor with restarter exits with await = true" in ticked {
      implicit ticker =>
        val test = constructor(true, Some(_ => true)) use { supervisor =>
          supervisor.supervise(IO.never[Unit]).void
        }

        test must nonTerminate
    }

    "await active fibers through a fiber when supervisor with restarter exits with await = true" in ticked {
      implicit ticker =>
        val test = constructor(true, Some(_ => true)) use { supervisor =>
          supervisor.supervise(IO.never[Unit]).void
        }

        test.start.flatMap(_.join).void must nonTerminate
    }

    "stop restarting fibers when supervisor exits with await = true" in ticked {
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
          _ <- IO(completed1 must beFalse)

          _ <- signal.release
          completed2 <- fiber.join.as(true).timeoutTo(200.millis, IO.pure(false))
          _ <- IO(completed2 must beTrue)

          count <- counter.get
          _ <- IO(count mustEqual 3)
        } yield ()

        test must completeAs(())
    }

    "cancel awaited fibers when exiting with error" in ticked { implicit ticker =>
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

      test must failAs(TestException)
    }

    "cancel awaited fibers when canceled" in ticked { implicit ticker =>
      val test = IO.deferred[Unit] flatMap { latch =>
        IO.deferred[Unit] flatMap { canceled =>
          val supervision = constructor(true, None) use { supervisor =>
            val action = (latch.complete(()) >> IO.never).onCancel(canceled.complete(()).void)
            supervisor.supervise(action) >> latch.get >> IO.canceled
          }

          supervision.guarantee(canceled.get)
        }
      }

      test must selfCancel
    }

    "check restart a fiber if it produces an error" in ticked { implicit ticker =>
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
          } <* counterR.get.flatMap(count => IO(count mustEqual 2))
        }
      }

      test must completeAs(42)
    }

    "check restart a fiber if it cancels" in ticked { implicit ticker =>
      val test = IO.ref(true) flatMap { raiseR =>
        IO.ref(0) flatMap { counterR =>
          val flipCancel = raiseR.set(false) >> IO.canceled.as(1)
          val action = (counterR.update(_ + 1) >> raiseR.get).ifM(flipCancel, IO.pure(42))

          constructor(true, Some(_.fold(true, _ => false, _ => false))).use { supervisor =>
            supervisor.supervise(action).flatMap(_.joinWithNever)
          } <* counterR.get.flatMap(count => IO(count mustEqual 2))
        }
      }

      test must completeAs(42)
    }

    "cancel inner fiber and ignore restart if outer canceled" in real {
      val test = IO.deferred[Unit] flatMap { latch =>
        constructor(true, Some(_.fold(true, _ => false, _ => false))).use { supervisor =>
          supervisor.supervise(latch.complete(()) >> IO.canceled) >> latch.get >> IO.canceled
        }
      }

      // if this doesn't work properly, the test will hang
      test.start.flatMap(_.join).as(ok).timeoutTo(2.seconds, IO(false must beTrue))
    }

    "cancel inner fiber and ignore restart if outer errored" in real {
      case object TestException extends RuntimeException

      val test = IO.deferred[Unit] flatMap { latch =>
        constructor(true, Some(_.fold(true, _ => false, _ => false))).use { supervisor =>
          supervisor.supervise(latch.complete(()) >> IO.canceled) >> latch.get >> IO.raiseError(
            TestException)
        }
      }

      // if this doesn't work properly, the test will hang
      test.start.flatMap(_.join).as(ok).timeoutTo(2.seconds, IO(false must beTrue))
    }
  }
}
