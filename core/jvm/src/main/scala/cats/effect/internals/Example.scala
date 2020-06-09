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

  /*
  The output of this program should be:
  1
  2
  3
  7
  8
  4
  5
  IOTrace: 0 omitted frames
    bind at org.simpleapp.example.Example$.run (Example.scala:70)
    bind at org.simpleapp.example.Example$.program (Example.scala:57)
    bind at org.simpleapp.example.Example$.$anonfun$program$1 (Example.scala:58)
    bind at org.simpleapp.example.Example$.$anonfun$program$2 (Example.scala:59)
    bind at org.simpleapp.example.Example$.$anonfun$program$3 (Example.scala:60)
    async at org.simpleapp.example.Example$.$anonfun$program$3 (Example.scala:63)
    bind at org.simpleapp.example.Example$.$anonfun$program$4 (Example.scala:62)
    bind at org.simpleapp.example.Example$.program2 (Example.scala:51)
    map at org.simpleapp.example.Example$.$anonfun$program2$1 (Example.scala:52)
    bind at org.simpleapp.example.Example$.$anonfun$program$7 (Example.scala:64)
    map at org.simpleapp.example.Example$.$anonfun$program$8 (Example.scala:65)
   */

  def print(msg: String): IO[Unit] =
    IO.delay(println(msg))

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
      _ <- IO.async[Int](cb => cb(Right(32)))
      _ <- IO.unit.bracket(_ => program2)(_ => IO.unit)
      _ <- print("5")
    } yield ()

  override def run(args: List[String]): IO[ExitCode] =
    for {
      _ <- IO.suspend(program).traced
      _ <- IO.delay("10")
      trace <- IO.backtrace
      _ <- trace.prettyPrint
      _ <- trace.compactPrint
    } yield ExitCode.Success

}
