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

import cats.Eq
import cats.data.EitherT
import cats.laws.discipline._
import cats.laws.discipline.SemigroupalTests.Isomorphisms

import org.scalacheck._, Prop.forAll
import org.scalacheck.util.Pretty

trait ConcurrentBracketTests[F[_], E] extends ConcurrentTests[F, E] with BracketTests[F, E] {

  val laws: ConcurrentBracketLaws[F, E]

  def concurrentBracket[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](
    implicit
      ArbFA: Arbitrary[F[A]],
      ArbFB: Arbitrary[F[B]],
      ArbFC: Arbitrary[F[C]],
      ArbFU: Arbitrary[F[Unit]],
      ArbFAtoB: Arbitrary[F[A => B]],
      ArbFBtoC: Arbitrary[F[B => C]],
      ArbE: Arbitrary[E],
      CogenA: Cogen[A],
      CogenB: Cogen[B],
      CogenFB: Cogen[F[B]],
      CogenC: Cogen[C],
      CogenE: Cogen[E],
      CogenCaseA: Cogen[Outcome[F, E, A]],
      CogenCaseB: Cogen[Outcome[F, E, B]],
      CogenCaseU: Cogen[Outcome[F, E, Unit]],
      EqFA: Eq[F[A]],
      EqFB: Eq[F[B]],
      EqFC: Eq[F[C]],
      EqFU: Eq[F[Unit]],
      EqE: Eq[E],
      EqFEitherEU: Eq[F[Either[E, Unit]]],
      EqFEitherEA: Eq[F[Either[E, A]]],
      EqFEitherAB: Eq[F[Either[A, B]]],
      EqFEitherUA: Eq[F[Either[Unit, A]]],
      EqFEitherAU: Eq[F[Either[A, Unit]]],
      EqFEitherEitherEAU: Eq[F[Either[Either[E, A], Unit]]],
      EqFEitherUEitherEA: Eq[F[Either[Unit, Either[E, A]]]],
      EqFOutcomeEA: Eq[F[Outcome[F, E, A]]],
      EqFOutcomeEU: Eq[F[Outcome[F, E, Unit]]],
      EqFABC: Eq[F[(A, B, C)]],
      EqFInt: Eq[F[Int]],
      iso: Isomorphisms[F],
      faPP: F[A] => Pretty,
      fbPP: F[B] => Pretty,
      fuPP: F[Unit] => Pretty,
      aFUPP: (A => F[Unit]) => Pretty,
      ePP: E => Pretty,
      foaPP: F[Outcome[F, E, A]] => Pretty,
      feauPP: F[Either[A, Unit]] => Pretty,
      feuaPP: F[Either[Unit, A]] => Pretty,
      fouPP: F[Outcome[F, E, Unit]] => Pretty)
      : RuleSet = {

    new RuleSet {
      val name = "concurrent (bracket)"
      val bases = Nil
      val parents = Seq(concurrent[A, B, C], bracket[A, B, C])

      val props = Seq(
        // "bracket canceled releases" -> forAll(laws.bracketCanceledReleases[A, B] _),
        "bracket uncancelable flatMap identity" -> forAll(laws.bracketUncancelableFlatMapIdentity[A, B] _))
    }
  }
}

object ConcurrentBracketTests {
  def apply[F[_], E](implicit F0: Concurrent[F, E] with Bracket[F, E]): ConcurrentBracketTests[F, E] = new ConcurrentBracketTests[F, E] {
    val laws = ConcurrentBracketLaws[F, E]
  }
}
