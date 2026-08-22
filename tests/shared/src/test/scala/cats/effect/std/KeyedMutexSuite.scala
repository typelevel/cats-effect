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
package std

import cats.arrow.FunctionK
import cats.syntax.all._

import scala.concurrent.duration._

final class KeyedMutexSuite extends BaseSuite {
  final override def executionTimeout = super.executionTimeout * 6

  tests("ConcurrentKeyedMutex", KeyedMutex.apply[IO, Int])
  tests("KeyedMutex with dual constructors", KeyedMutex.in[IO, IO, Int])
  tests("MapK'd KeyedMutex", KeyedMutex[IO, Int].map(_.mapK[IO](FunctionK.id)))

  def tests(name: String, keyedMutex: IO[KeyedMutex[IO, Int]]) = {
    real(
      s"${name} should execute action if free in the given key"
    ) {
      val p = keyedMutex.flatMap { m => m.lock(key = 0).surround(IO.unit) }

      p.mustEqual(())
    }

    real(
      s"${name} should be reusable in the same key"
    ) {
      val p = keyedMutex.flatMap { m =>
        m.lock(key = 0).surround(IO.unit) >>
          m.lock(key = 0).surround(IO.unit)
      }

      p.mustEqual(())
    }

    ticked(
      s"${name} should block action if not free in the given key"
    ) { implicit ticker =>
      val p = keyedMutex.flatMap { m =>
        m.lock(key = 0).surround(IO.never) >>
          m.lock(key = 0).surround(IO.unit)
      }

      assertNonTerminate(p)
    }

    ticked(
      s"${name} should not block action if using a different key"
    ) { implicit ticker =>
      val p = keyedMutex.flatMap { m =>
        IO.race(
          m.lock(key = 0).surround(IO.never),
          IO.sleep(1.second) >> m.lock(key = 1).surround(IO.unit)
        ).void
      }

      assertCompleteAs(p, ())
    }

    ticked(
      s"${name} should support concurrent usage in the same key"
    ) { implicit ticker =>
      val p = keyedMutex.flatMap { m =>
        val usage = IO.sleep(1.second) >> m.lock(key = 0).surround(IO.sleep(1.second))

        (usage, usage).parTupled.void
      }

      assertCompleteAs(p, ())
    }

    ticked(
      s"${name} should support concurrent usage in different keys"
    ) { implicit ticker =>
      val p = keyedMutex.flatMap { m =>
        def usage(key: Int): IO[Unit] =
          IO.sleep(1.second) >> m.lock(key).surround(IO.sleep(1.second))

        List.range(start = 1, end = 10).parTraverse_(usage)
      }

      assertCompleteAs(p, ())
    }
  }
}
