/*
 * Copyright 2020-2025 Typelevel
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

package cats
package effect
package kernel

import scala.concurrent.duration._

class MiniSemaphoreSuite extends BaseSuite { outer =>

  real("throw on negative n") {
    IO.defer(MiniSemaphore[IO](-1)).mustFailWith[IllegalArgumentException]
  }

  ticked("block if no permits available") { implicit ticker =>
    assertNonTerminate(MiniSemaphore[IO](0).flatMap { sem => sem.withPermit(IO.unit) })
  }

  real("execute action if permit is available for it") {
    MiniSemaphore[IO](1).flatMap { sem => sem.withPermit(IO.unit).mustEqual(()) }
  }

  ticked("unblock when permit is released") { implicit ticker =>
    val p =
      for {
        sem <- MiniSemaphore[IO](1)
        ref <- IO.ref(false)
        _ <- sem.withPermit { IO.sleep(1.second) >> ref.set(true) }.start
        _ <- IO.sleep(500.millis)
        _ <- sem.withPermit(IO.unit)
        v <- ref.get
      } yield v

    assertCompleteAs(p, true)
  }

  real("release permit if withPermit errors") {
    for {
      sem <- MiniSemaphore[IO](1)
      _ <- sem.withPermit(IO.raiseError(new Exception)).attempt
      res <- sem.withPermit(IO.unit).mustEqual(())
    } yield res
  }

  ticked("release permit if action gets canceled") { implicit ticker =>
    val p =
      for {
        sem <- MiniSemaphore[IO](1)
        fiber <- sem.withPermit(IO.never).start
        _ <- IO.sleep(1.second)
        _ <- fiber.cancel
        _ <- sem.withPermit(IO.unit)
      } yield ()

    assertCompleteAs(p, ())
  }

  ticked("allow cancelation if blocked waiting for permit") { implicit ticker =>
    val p = for {
      sem <- MiniSemaphore[IO](0)
      ref <- IO.ref(false)
      f <- sem.withPermit(IO.unit).onCancel(ref.set(true)).start
      _ <- IO.sleep(1.second)
      _ <- f.cancel
      v <- ref.get
    } yield v

    assertCompleteAs(p, true)
  }

  ticked("not release permit when an acquire gets canceled") { implicit ticker =>
    val p = for {
      sem <- MiniSemaphore[IO](0)
      _ <- sem.withPermit(IO.unit).timeout(1.second).attempt
      _ <- sem.withPermit(IO.unit)
    } yield ()

    assertNonTerminate(p)
  }

}
