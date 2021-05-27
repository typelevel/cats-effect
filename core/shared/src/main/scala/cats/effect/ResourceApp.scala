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

trait ResourceApp { self =>
  def run(args: List[String]): Resource[IO, ExitCode]

  final def main(args: Array[String]): Unit = {
    val ioApp = new IOApp {
      override def run(args: List[String]): IO[ExitCode] =
        self.run(args).use(IO.pure(_))
    }

    ioApp.main(args)
  }
}

object ResourceApp {
  trait Simple extends ResourceApp {
    def run: Resource[IO, Unit]
    final def run(args: List[String]): Resource[IO, ExitCode] = run.as(ExitCode.Success)
  }

  trait Forever { self =>
    def run(args: List[String]): Resource[IO, Unit]

    final def main(args: Array[String]): Unit = {
      val ioApp = new IOApp {
        override def run(args: List[String]): IO[ExitCode] =
          self.run(args).useForever
      }

      ioApp.main(args)
    }
  }
}
