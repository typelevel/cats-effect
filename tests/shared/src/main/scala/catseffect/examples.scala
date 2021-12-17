/*
 * Copyright 2020-2021 Typelevel
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
import cats.syntax.all._

package examples {

  object HelloWorld extends IOApp.Simple {
    def run: IO[Unit] =
      IO(println("Hello, World!"))
  }

  object Arguments extends IOApp {
    def run(args: List[String]): IO[ExitCode] =
      args.traverse_(s => IO(println(s))).as(ExitCode.Success)
  }

  object FatalError extends IOApp {
    def run(args: List[String]): IO[ExitCode] =
      IO(throw new OutOfMemoryError("Boom!")).as(ExitCode.Success)
  }

  object Canceled extends IOApp {
    def run(args: List[String]): IO[ExitCode] =
      IO.canceled.as(ExitCode.Success)
  }

  object GlobalRacingInit extends IOApp {

    def foo(): Unit = {
      // touch the global runtime to force its initialization
      val _ = cats.effect.unsafe.implicits.global
      ()
    }

    foo()

    // indirect assertion that we *don't* use the custom config
    override def runtimeConfig = sys.error("boom")

    def run(args: List[String]): IO[ExitCode] =
      IO.pure(ExitCode.Success)
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
      _ <- fibers.traverse(_.join)
    } yield ()
  }

  object WorkerThreadInterrupt extends IOApp.Simple {
    val run =
      IO(Thread.currentThread().interrupt()) *> IO(Thread.sleep(1000L))
  }
}
