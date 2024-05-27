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
import cats.effect.unsafe.IORuntime
import cats.syntax.all._

import scala.collection.mutable

package object examples {
  def exampleExecutionContext = IORuntime.defaultComputeExecutionContext
}

package examples {

  object NativeRunner {
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
    register(LiveFiberSnapshot)
    register(FatalErrorUnsafeRun)
    register(Finalizers)
    register(LeakedFiber)
    register(CustomRuntime)
    register(CpuStarvation)

    def main(args: Array[String]): Unit = {
      val app = args(0)
      apps
        .get(app)
        .map(_().main(args.tail))
        .orElse(rawApps.get(app).map(_().main(args.tail)))
        .get
    }
  }

  object FatalErrorUnsafeRun extends IOApp {
    def run(args: List[String]): IO[ExitCode] =
      for {
        _ <- (0 until 100).toList.traverse(_ => IO.never.start)
        _ <- IO(throw new OutOfMemoryError("Boom!")).start
        _ <- IO.never[Unit]
      } yield ExitCode.Success
  }

}
