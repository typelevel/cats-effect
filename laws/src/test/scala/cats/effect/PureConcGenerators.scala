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

import pure._

import org.scalacheck.{Arbitrary, Cogen}

object PureConcGenerators {
  import OutcomeGenerators._

  implicit def cogenPureConc[E: Cogen, A: Cogen]: Cogen[PureConc[E, A]] = Cogen[Outcome[Option, E, A]].contramap(run(_))

  val generators = new ConcurrentGenerators[PureConc[Int, *], Int] with BracketGenerators[PureConc[Int, *], Int] {

    val arbitraryE: Arbitrary[Int] = implicitly[Arbitrary[Int]]

    val cogenE: Cogen[Int] = Cogen[Int]

    val F: ConcurrentBracket[PureConc[Int, *], Int] = concurrentBForPureConc[Int]

    def cogenCase[A: Cogen]: Cogen[Outcome[PureConc[Int, *], Int, A]] = OutcomeGenerators.cogenOutcome[PureConc[Int, *], Int, A]
  }

  implicit def arbitraryPureConc[A: Arbitrary: Cogen]: Arbitrary[PureConc[Int, A]] =
    Arbitrary(generators.generators[A])
}
