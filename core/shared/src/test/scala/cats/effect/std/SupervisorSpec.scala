/*
 * Copyright 2020 Typelevel
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

import cats.effect.kernel.{Deferred, Outcome}

class SupervisorSpec extends BaseSpec {

  "Supervisor" should {
    "start a fiber that completes successfully" in ticked { implicit ticker =>
      val test = Supervisor[IO].use { supervisor => supervisor.supervise(IO(1)).flatten }

      test must completeAs(Outcome.succeeded[IO, Throwable, Int](IO.pure(1)))
    }

    "start a fiber that raises an error" in ticked { implicit ticker =>
      val t = new Throwable("failed")
      val test = Supervisor[IO].use { supervisor =>
        supervisor.supervise(IO.raiseError[Unit](t)).flatten
      }

      test must completeAs(Outcome.errored[IO, Throwable, Unit](t))
    }

    "start a fiber that self-cancels" in ticked { implicit ticker =>
      val test = Supervisor[IO].use { supervisor => supervisor.supervise(IO.canceled).flatten }

      test must completeAs(Outcome.canceled[IO, Throwable, Unit])
    }

    "cancel active fibers when supervisor exits" in ticked { implicit ticker =>
      val test = for {
        joinDef <- Deferred[IO, IO[Outcome[IO, Throwable, Unit]]]
        _ <- Supervisor[IO].use { supervisor =>
          supervisor.supervise(IO.never[Unit]).flatMap(joinDef.complete(_))
        }
        outcome <- joinDef.get.flatten
      } yield outcome

      test must completeAs(Outcome.canceled[IO, Throwable, Unit])
    }

    "raise an error if starting a fiber after supervisor exits" in real {
      val test = for {
        // never do this...
        supervisor <- Supervisor[IO].use { supervisor => IO.pure(supervisor) }
        _ <- supervisor.supervise(IO.pure(1))
      } yield ()

      test.mustFailWith[IllegalStateException]
    }
  }

}
