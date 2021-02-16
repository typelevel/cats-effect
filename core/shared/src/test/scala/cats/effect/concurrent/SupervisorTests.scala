/*
 * Copyright (c) 2017-2021 The Typelevel Cats-effect Project Developers
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

package cats.effect.concurrent

import cats.effect._

import scala.concurrent.ExecutionContext


class SupervisorTests extends CatsEffectSuite {

  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val timer: cats.effect.Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  test("start a fiber that completes successfully") {
    Supervisor[IO].use { supervisor =>
      supervisor.supervise(IO(1)).flatMap(_.join)
    }.map(x => assertEquals(x, 1))
  }

  test("start a fiber that raises an error") {
    val t = new Throwable("failed")
    Supervisor[IO].use { supervisor =>
      supervisor.supervise(IO.raiseError[Unit](t)).flatMap(_.join)
    }.attempt.map(x => assertEquals(x, Left(t)))
  }

  test("cancel active fibers when supervisor exits") {
    for {
      testPassed <- Deferred[IO, Boolean]
      gate <- Deferred[IO, Unit]
      _ <- Supervisor[IO].use { supervisor =>
        supervisor.supervise(
          (gate.complete(()) *> IO.never).guarantee(testPassed.complete(true))
        ) *> gate.get
      }
      result <- testPassed.get
    } yield assert(result)
  }
}
