/*
 * Copyright 2020 Daniel Spiewak
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

package ce3
package laws

import cats.{~>, Applicative, ApplicativeError, Eq, FlatMap, Monad, Monoid, Show}
import cats.data.{EitherK, StateT}
import cats.free.FreeT
import cats.implicits._

import coop.ThreadT

import playground._

import org.scalacheck.{Arbitrary, Cogen, Gen}, Arbitrary.arbitrary
import org.scalacheck.util.Pretty

import org.specs2.ScalaCheck
import org.specs2.matcher.Matcher
import org.specs2.mutable._

import org.typelevel.discipline.specs2.mutable.Discipline

class PureConcSpec extends Specification with Discipline with ScalaCheck {
  type F[A] = PureConc[Int, A]

  import OutcomeGenerators._

  implicit def cogenPureConc[E: Cogen, A: Cogen]: Cogen[PureConc[E, A]] = Cogen[Outcome[Option, E, A]].contramap(run(_))

  val generators = new ConcurrentGenerators[F, Int] with BracketGenerators[F, Int] {
    
    val arbitraryE: Arbitrary[Int] = implicitly[Arbitrary[Int]]
    
    val cogenE: Cogen[Int] = Cogen[Int]
    
    val F: ConcurrentBracket[F, Int] = concurrentBForPureConc[Int]

    def cogenCase[A: Cogen]: Cogen[Outcome[F, Int, A]] = OutcomeGenerators.cogenOutcome[F, Int, A]
  }

  implicit def arbitraryPureConc[A: Arbitrary: Cogen]: Arbitrary[F[A]] = Arbitrary(generators.generators[A])

  implicit def prettyFromShow[A: Show](a: A): Pretty =
    Pretty.prettyString(a.show)

  def beEqv[A: Eq: Show](expect: A): Matcher[A] = be_===[A](expect)

  def be_===[A: Eq: Show](expect: A): Matcher[A] = (result: A) =>
    (result === expect, s"${result.show} === ${expect.show}", s"${result.show} !== ${expect.show}")

  checkAll(
    "PureConc",
    ConcurrentBracketTests[PureConc[Int, ?], Int].concurrentBracket[Int, Int, Int])
}
