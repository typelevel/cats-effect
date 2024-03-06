/*
 * Copyright 2020-2024 Typelevel
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
package kernel

import cats.{Eq, Eval, Id, MonadError}
import cats.effect.kernel.testkit.OutcomeGenerators
import cats.laws.discipline.{ApplicativeErrorTests, MonadErrorTests}

import org.specs2.mutable.Specification
import org.typelevel.discipline.specs2.mutable.Discipline

class OutcomeSpec extends Specification with Discipline {
  import OutcomeGenerators._

  {
    // this whole dance is to help scala 2.12 compile this test
    type OutcomeIdInt[A] = Outcome[Id, Int, A]

    implicit def monadErrorOutcomeIdInt: MonadError[OutcomeIdInt, Int] =
      Outcome.monadError[Id, Int]

    implicit def eqOutcomeId[A]: Eq[OutcomeIdInt[A]] = Outcome.eq[Id, Int, A]

    implicit def eqId[A]: Eq[Id[A]] = Eq.instance(_ == _)

    checkAll(
      "Outcome[Id, Int, *]",
      MonadErrorTests[OutcomeIdInt, Int].monadError[Int, Int, Int])
  }

  checkAll(
    "Outcome[Option, Int, *]",
    MonadErrorTests[Outcome[Option, Int, *], Int].monadError[Int, Int, Int])

  checkAll(
    "Outcome[Eval, Int, *]",
    ApplicativeErrorTests[Outcome[Eval, Int, *], Int].applicativeError[Int, Int, Int])
}
