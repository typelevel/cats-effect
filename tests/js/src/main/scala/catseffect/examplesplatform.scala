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

import org.scalajs.macrotaskexecutor.MacrotaskExecutor

import scala.annotation.nowarn
import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.scalajs.js

package object examples {
  def exampleExecutionContext = MacrotaskExecutor
}

package examples {

  object JSRunner {
    val apps = mutable.Map.empty[String, () => IOApp]
    def register(app: IOApp): Unit = apps(app.getClass.getName.init) = () => app
    def registerLazy(name: String, app: => IOApp): Unit =
      apps(name) = () => app

    val rawApps = mutable.Map.empty[String, () => RawApp]
    def registerRaw(app: RawApp): Unit = rawApps(app.getClass.getName.init) = () => app

    register(HelloWorld)
    register(Arguments)
    register(NonFatalError)
    register(FatalError)
    register(RaiseFatalErrorAttempt)
    register(RaiseFatalErrorHandle)
    register(RaiseFatalErrorMap)
    register(RaiseFatalErrorFlatMap)
    registerRaw(FatalErrorRaw)
    register(Canceled)
    registerLazy("catseffect.examples.GlobalRacingInit", GlobalRacingInit)
    registerLazy("catseffect.examples.GlobalShutdown", GlobalShutdown)
    register(ShutdownHookImmediateTimeout)
    register(LiveFiberSnapshot)
    register(FatalErrorUnsafeRun)
    register(Finalizers)
    register(LeakedFiber)
    register(UndefinedProcessExit)
    register(CustomRuntime)
    register(CpuStarvation)

    @nowarn("msg=never used")
    def main(paperweight: Array[String]): Unit = {
      val args = js.Dynamic.global.process.argv.asInstanceOf[js.Array[String]]
      val app = args(2)
      if (app == UndefinedProcessExit.getClass.getName.init)
        // emulates the situation in browsers
        js.Dynamic.global.process.exit = js.undefined
      args.shift()
      apps
        .get(app)
        .map(_().main(Array.empty))
        .orElse(rawApps.get(app).map(_().main(Array.empty)))
        .get
    }
  }

  object ShutdownHookImmediateTimeout extends IOApp.Simple {

    override protected def runtimeConfig =
      super.runtimeConfig.copy(shutdownHookTimeout = Duration.Zero)

    val run: IO[Unit] =
      IO(js.Dynamic.global.process.exit(0)).void.uncancelable
  }

  object FatalErrorUnsafeRun extends IOApp {
    def run(args: List[String]): IO[ExitCode] =
      for {
        _ <- (0 until 100).toList.traverse(_ => IO.never.start)
        _ <- IO(throw new OutOfMemoryError("Boom!")).start
        _ <- IO.never[Unit]
      } yield ExitCode.Success
  }

  object Finalizers extends IOApp {
    def writeToFile(string: String, file: String): IO[Unit] =
      IO(js.Dynamic.global.require("fs").writeFileSync(file, string)).void

    def run(args: List[String]): IO[ExitCode] =
      (IO(println("Started")) >> IO.never)
        .onCancel(writeToFile("canceled", args.head))
        .as(ExitCode.Success)
  }

  object UndefinedProcessExit extends IOApp {
    def run(args: List[String]): IO[ExitCode] = IO.pure(ExitCode.Success)
  }

}
