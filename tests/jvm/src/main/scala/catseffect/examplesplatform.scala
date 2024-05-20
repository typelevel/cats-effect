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
import cats.syntax.all._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.util.concurrent.atomic.AtomicReference

package object examples {
  def exampleExecutionContext = ExecutionContext.global
}

package examples {

  object ShutdownHookImmediateTimeout extends IOApp.Simple {

    override protected def runtimeConfig =
      super.runtimeConfig.copy(shutdownHookTimeout = Duration.Zero)

    val run: IO[Unit] =
      IO.blocking(System.exit(0)).uncancelable
  }

  object FatalErrorUnsafeRun extends IOApp {
    import cats.effect.unsafe.implicits.global

    def run(args: List[String]): IO[ExitCode] =
      for {
        _ <- (0 until 100).toList.traverse(_ => IO.blocking(IO.never.unsafeRunSync()).start)
        _ <- IO.blocking(IO(throw new OutOfMemoryError("Boom!")).start.unsafeRunSync())
        _ <- IO.never[Unit]
      } yield ExitCode.Success
  }

  object EvalOnMainThread extends IOApp {
    def run(args: List[String]): IO[ExitCode] =
      IO(Thread.currentThread().getId()).evalOn(MainThread) map {
        case 1L => ExitCode.Success
        case _ => ExitCode.Error
      }
  }

  object MainThreadReportFailure extends IOApp {

    val exitCode = new AtomicReference[ExitCode](ExitCode.Error)

    override def reportFailure(err: Throwable): IO[Unit] =
      IO(exitCode.set(ExitCode.Success))

    def run(args: List[String]): IO[ExitCode] =
      IO.raiseError(new Exception).startOn(MainThread) *>
        IO.sleep(1.second) *> IO(exitCode.get)

  }

  object BlockedThreads extends IOApp.Simple {

    override protected def blockedThreadDetectionEnabled = true

    // Loop prevents other worker threads from being parked and hence not
    // performing the blocked check
    val run =
      IO.unit.foreverM.start >> IO(Thread.sleep(2.seconds.toMillis))
  }
}
