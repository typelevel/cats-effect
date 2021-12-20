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

import scala.concurrent.duration.Duration

import java.io.File

package examples {

  object ShutdownHookImmediateTimeout extends IOApp.Simple {

    override protected def runtimeConfig =
      super.runtimeConfig.copy(shutdownHookTimeout = Duration.Zero)

    val run: IO[Unit] =
      IO(System.exit(0)).uncancelable
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

  object Finalizers extends IOApp {
    import java.io.FileWriter

    def writeToFile(string: String, file: File): IO[Unit] =
      IO(new FileWriter(file)).bracket { writer => IO(writer.write(string)) }(writer =>
        IO(writer.close()))

    def run(args: List[String]): IO[ExitCode] =
      (IO(println("Started")) >> IO.never)
        .onCancel(writeToFile("canceled", new File(args.head)))
        .as(ExitCode.Success)
  }

}
