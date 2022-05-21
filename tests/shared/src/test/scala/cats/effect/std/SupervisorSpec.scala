/*
 * Copyright 2020-2022 Typelevel
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
package std

import org.specs2.specification.core.Fragments

class SupervisorSpec extends BaseSpec {

  "Supervisor" should {
    supervisorTests("Supervisor (concurrent)", Supervisor.applyForConcurrent)
    supervisorTests("Supervisor (async)", Supervisor.applyForAsync)
  }

  private def supervisorTests(
      name: String,
      constructor: Boolean => Resource[IO, Supervisor[IO]]): Fragments = {

    name >> {

      "start a fiber that completes successfully" in ticked { implicit ticker =>
        val test = constructor(false).use { supervisor =>
          supervisor.supervise(IO(1)).flatMap(_.join)
        }

        test must completeAs(Outcome.succeeded[IO, Throwable, Int](IO.pure(1)))
      }

      "start a fiber that raises an error" in ticked { implicit ticker =>
        val t = new Throwable("failed")
        val test = constructor(false).use { supervisor =>
          supervisor.supervise(IO.raiseError[Unit](t)).flatMap(_.join)
        }

        test must completeAs(Outcome.errored[IO, Throwable, Unit](t))
      }

      "start a fiber that self-cancels" in ticked { implicit ticker =>
        val test = constructor(false).use { supervisor =>
          supervisor.supervise(IO.canceled).flatMap(_.join)
        }

        test must completeAs(Outcome.canceled[IO, Throwable, Unit])
      }

      "cancel active fibers when supervisor exits" in ticked { implicit ticker =>
        val test = for {
          fiber <- constructor(false).use { supervisor => supervisor.supervise(IO.never[Unit]) }
          outcome <- fiber.join
        } yield outcome

        test must completeAs(Outcome.canceled[IO, Throwable, Unit])
      }

      "await active fibers when supervisor exits with await = true" in ticked {
        implicit ticker =>
          val test = constructor(true).use { supervisor =>
            supervisor.supervise(IO.never[Unit]).void
          }

          test must nonTerminate
      }
    }
  }
}
