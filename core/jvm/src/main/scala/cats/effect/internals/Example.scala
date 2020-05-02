/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

package cats.effect.internals

import cats.effect.{ExitCode, FiberRef, IO, IOApp}

object Example extends IOApp {

  def print(msg: Int): IO[Unit] =
    IO.delay(println(msg))

  // Should print 5 and then 10
  def program: IO[Unit] =
    for {
      ref <- FiberRef.of(5)
      a1 <- ref.get
      _ <- print(a1)
      _ <- ref.set(10)
      _ <- IO.shift
      a2 <- ref.get
      _ <- print(a2)
    } yield ()

  override def run(args: List[String]): IO[ExitCode] =
    program.as(ExitCode.Success)
}
