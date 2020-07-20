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

package cats.effect.testkit

import cats.effect.kernel.{Concurrent, Outcome}
import cats.effect.testkit.pure._

import org.scalacheck.{Arbitrary, Cogen}

object PureConcGenerators {
  import OutcomeGenerators._

  implicit def cogenPureConc[E: Cogen, A: Cogen]: Cogen[PureConc[E, A]] =
    Cogen[Outcome[Option, E, A]].contramap(run(_))

  def generators[E: Arbitrary: Cogen] =
    new ConcurrentGenerators[PureConc[E, *], E] {

      val arbitraryE: Arbitrary[E] = implicitly[Arbitrary[E]]

      val cogenE: Cogen[E] = Cogen[E]

      val F: Concurrent[PureConc[E, *], E] = concurrentForPureConc[E]

      def cogenCase[A: Cogen]: Cogen[Outcome[PureConc[E, *], E, A]] =
        OutcomeGenerators.cogenOutcome[PureConc[E, *], E, A]
    }

  implicit def arbitraryPureConc[E: Arbitrary: Cogen, A: Arbitrary: Cogen]
      : Arbitrary[PureConc[E, A]] =
    Arbitrary(generators[E].generators[A])
}
