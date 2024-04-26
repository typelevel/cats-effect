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

package catseffect

import cats.effect.{ExitCode, IO, IOApp}
import cats.effect.std.{Console, Random}
import cats.effect.unsafe.{IORuntime, IORuntimeConfig, Scheduler}
import cats.syntax.all._

import scala.concurrent.Await
import scala.concurrent.duration._

package examples {

  import java.util.concurrent.TimeoutException

  object HelloWorld extends IOApp.Simple {
    def run: IO[Unit] =
      IO(println("Hello, World!"))
  }

  object Arguments extends IOApp {
    def run(args: List[String]): IO[ExitCode] =
      args.traverse_(s => IO(println(s))).as(ExitCode.Success)
  }

  object NonFatalError extends IOApp {
    def run(args: List[String]): IO[ExitCode] =
      IO(throw new RuntimeException("Boom!")).as(ExitCode.Success)
  }

  object FatalError extends IOApp {
    def run(args: List[String]): IO[ExitCode] =
      IO(throw new OutOfMemoryError("Boom!"))
        .attempt
        .flatMap(_ => IO.println("sadness"))
        .as(ExitCode.Success)
  }

  object FatalErrorRaw extends RawApp {
    def main(args: Array[String]): Unit = {
      import cats.effect.unsafe.implicits._
      val action =
        IO(throw new OutOfMemoryError("Boom!")).attempt.flatMap(_ => IO.println("sadness"))
      action.unsafeToFuture()
      ()
    }
  }

  object FatalErrorShutsDownRt extends RawApp {
    def main(args: Array[String]): Unit = {
      val rt = cats.effect.unsafe.IORuntime.global
      @volatile var thread: Thread = null
      val action = for {
        // make sure a blocking thread exists, save it:
        _ <- IO.blocking {
          thread = Thread.currentThread()
        }
        // get back on the WSTP:
        _ <- IO.cede
        // fatal error on the WSTP thread:
        _ <- IO {
          throw new OutOfMemoryError("Boom!")
        }.attempt.flatMap(_ => IO.println("sadness (attempt)"))
      } yield ()
      val fut = action.unsafeToFuture()(rt)
      try {
        Await.ready(fut, atMost = 2.seconds)
      } catch {
        case _: TimeoutException => println("sadness (timeout)")
      }
      Thread.sleep(500L)
      // by now the WSTP (and all its threads) must've been shut down:
      if (thread eq null) println("sadness (thread is null)")
      else if (thread.isAlive()) println("sadness (thread is alive)")
      println("done")
    }
  }

  object RaiseFatalErrorAttempt extends IOApp {
    def run(args: List[String]): IO[ExitCode] = {
      IO.raiseError[Unit](new OutOfMemoryError("Boom!"))
        .attempt
        .flatMap(_ => IO.println("sadness"))
        .as(ExitCode.Success)
    }
  }

  object RaiseFatalErrorHandle extends IOApp {
    def run(args: List[String]): IO[ExitCode] = {
      IO.raiseError[Unit](new OutOfMemoryError("Boom!"))
        .handleError(_ => ())
        .flatMap(_ => IO.println("sadness"))
        .as(ExitCode.Success)
    }
  }

  object RaiseFatalErrorMap extends IOApp {
    def run(args: List[String]): IO[ExitCode] = {
      IO.raiseError[Unit](new OutOfMemoryError("Boom!"))
        .map(_ => ())
        .handleError(_ => ())
        .flatMap(_ => IO.println("sadness"))
        .as(ExitCode.Success)
    }
  }

  object RaiseFatalErrorFlatMap extends IOApp {
    def run(args: List[String]): IO[ExitCode] = {
      IO.raiseError[Unit](new OutOfMemoryError("Boom!"))
        .flatMap(_ => IO(()))
        .handleError(_ => ())
        .flatMap(_ => IO.println("sadness"))
        .as(ExitCode.Success)
    }
  }

  object Canceled extends IOApp {
    def run(args: List[String]): IO[ExitCode] =
      IO.canceled.as(ExitCode.Success)
  }

  object GlobalRacingInit extends IOApp {

    var r: IORuntime = null

    def foo(): Unit = {
      // touch the global runtime to force its initialization
      r = cats.effect.unsafe.implicits.global
      ()
    }

    foo()

    def run(args: List[String]): IO[ExitCode] =
      Console[IO].errorln("boom").whenA(!r.eq(runtime)) >> IO.pure(ExitCode.Success)
  }

  object GlobalShutdown extends IOApp {

    var r: IORuntime = null

    def foo(): Unit = {
      // touch the global runtime to force its initialization
      r = cats.effect.unsafe.implicits.global
      ()
    }

    foo()
    r.shutdown()

    def run(args: List[String]): IO[ExitCode] =
      Console[IO].errorln("boom").whenA(r.eq(runtime)) >> IO.pure(ExitCode.Success)
  }

  object LiveFiberSnapshot extends IOApp.Simple {

    import scala.concurrent.duration._

    lazy val loop: IO[Unit] =
      IO.unit.map(_ => ()) >>
        IO.unit.flatMap(_ => loop)

    val run = for {
      fibers <- loop.timeoutTo(5.seconds, IO.unit).start.replicateA(32)

      sleeper = for {
        _ <- IO.unit
        _ <- IO.unit
        _ <- IO.sleep(3.seconds)
      } yield ()

      _ <- sleeper.start
      _ <- IO.println("ready")
      _ <- fibers.traverse(_.join)
    } yield ()
  }

  object WorkerThreadInterrupt extends IOApp.Simple {
    val run =
      IO(Thread.currentThread().interrupt()) *> IO(Thread.sleep(1000L))
  }

  object LeakedFiber extends IOApp.Simple {
    val run = IO.cede.foreverM.start.void
  }

  object CustomRuntime extends IOApp.Simple {
    override lazy val runtime = IORuntime(
      exampleExecutionContext,
      exampleExecutionContext,
      Scheduler.createDefaultScheduler()._1,
      () => (),
      IORuntimeConfig()
    )

    def run = IO {
      if (runtime eq IORuntime.global)
        ()
      else
        throw new AssertionError("Custom runtime not installed as global")
    }
  }

  // The parameters here were chosen experimentally and seem to be
  // relatively reliable. The trick is finding a balance such that
  // we end up with every WSTP thread being blocked but not permanently
  // so that the starvation detector fiber still gets to run and
  // therefore report its warning
  object CpuStarvation extends IOApp.Simple {

    override protected def runtimeConfig: IORuntimeConfig = IORuntimeConfig().copy(
      cpuStarvationCheckInterval = 200.millis,
      cpuStarvationCheckInitialDelay = 0.millis,
      cpuStarvationCheckThreshold = 0.2d
    )

    private def fib(n: Long): Long = n match {
      case 0 => 0
      case 1 => 1
      case n => fib(n - 1) + fib(n - 2)
    }

    val run = Random.scalaUtilRandom[IO].flatMap { rand =>
      // jitter to give the cpu starvation checker a chance to run at all
      val jitter = rand.nextIntBounded(100).flatMap(n => IO.sleep(n.millis))

      (jitter >> IO(fib(42)))
        .replicateA_(2)
        .parReplicateA_(Runtime.getRuntime().availableProcessors() * 4)
    }
  }
}
