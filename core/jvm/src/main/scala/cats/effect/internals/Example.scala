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

package org.simpleapp.example

import cats.effect.{ExitCode, IO, IOApp}

object Example extends IOApp {

  def print(msg: String): IO[Unit] =
    IO(println(msg))

  def program2: IO[Unit] =
    for {
      _ <- print("3")
      _ <- print("4")
    } yield ()

  def program: IO[Unit] =
    for {
      _ <- print("1")
      _ <- print("2")
      _ <- IO.shift
      _ <- program2
      _ <- print("5")
    } yield ()

  override def run(args: List[String]): IO[ExitCode] =
    for {
      _ <- IO.suspend(program).traced
      trace <- IO.backtrace
      _ <- trace.compactPrint
    } yield ExitCode.Success

}
