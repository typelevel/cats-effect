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

package cats.effect

import cats.syntax.all._

trait IOApp extends IOAppPlatform {
  def run(args: List[String]): IO[ExitCode]
}

object IOApp {

  trait Simple extends IOApp {
    def run: IO[Unit]
    final def run(args: List[String]): IO[ExitCode] = run.as(ExitCode.Success)
  }

  trait ResourceApp extends IOApp {
    def runResource(args: List[String]): Resource[IO, ExitCode]
    final def run(args: List[String]): IO[ExitCode] = runResource(args).use(IO.pure(_))
  }

  trait SimpleResourceApp extends ResourceApp {
    def runResource: Resource[IO, Unit]
    final def runResource(args: List[String]): Resource[IO, ExitCode] =
      runResource.as(ExitCode.Success)
  }

}
