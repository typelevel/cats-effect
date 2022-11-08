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

package cats
package effect
package laws

import cats.effect.kernel.Spawn
import cats.effect.kernel.testkit.pure._
import cats.syntax.all._

import org.specs2.mutable.Specification

class GenSpawnSpec extends Specification { outer =>

  type F[A] = PureConc[Unit, A]
  val F = Spawn[F]

  "spawn" should {
    "raceOutcome" should {
      "not hang" in {
        Eq[F[Unit]].eqv(F.raceOutcome(F.never, F.canceled).void, F.unit)
      }
    }
  }

}
