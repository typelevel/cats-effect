/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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
package discipline

import cats.data._
import cats.laws.discipline._
import cats.laws.discipline.SemigroupalTests.Isomorphisms
import org.scalacheck._, Prop.forAll

trait AsyncTests[F[_]] extends SyncTests[F] {
  def laws: AsyncLaws[F]

  def async[A: Arbitrary: Eq, B: Arbitrary: Eq, C: Arbitrary: Eq](implicit
                                                                  ArbFA: Arbitrary[F[A]],
                                                                  ArbFB: Arbitrary[F[B]],
                                                                  ArbFC: Arbitrary[F[C]],
                                                                  ArbFU: Arbitrary[F[Unit]],
                                                                  ArbFAtoB: Arbitrary[F[A => B]],
                                                                  ArbFBtoC: Arbitrary[F[B => C]],
                                                                  ArbT: Arbitrary[Throwable],
                                                                  CogenA: Cogen[A],
                                                                  CogenB: Cogen[B],
                                                                  CogenC: Cogen[C],
                                                                  CogenT: Cogen[Throwable],
                                                                  EqFA: Eq[F[A]],
                                                                  EqFB: Eq[F[B]],
                                                                  EqFC: Eq[F[C]],
                                                                  EqFU: Eq[F[Unit]],
                                                                  EqT: Eq[Throwable],
                                                                  EqFEitherTU: Eq[F[Either[Throwable, Unit]]],
                                                                  EqFEitherTA: Eq[F[Either[Throwable, A]]],
                                                                  EqEitherTFTA: Eq[EitherT[F, Throwable, A]],
                                                                  EqFABC: Eq[F[(A, B, C)]],
                                                                  EqFInt: Eq[F[Int]],
                                                                  iso: Isomorphisms[F],
                                                                  params: Parameters): RuleSet =
    new RuleSet {
      val name = "async"
      val bases = Nil
      val parents = Seq(sync[A, B, C])
      val props = {
        val default = Seq(
          "async right is pure" -> forAll(laws.asyncRightIsPure[A] _),
          "async left is raiseError" -> forAll(laws.asyncLeftIsRaiseError[A] _),
          "repeated async evaluation not memoized" -> forAll(laws.repeatedAsyncEvaluationNotMemoized[A] _),
          "repeated asyncF evaluation not memoized" -> forAll(laws.repeatedAsyncFEvaluationNotMemoized[A] _),
          "propagate errors through bind (async)" -> forAll(laws.propagateErrorsThroughBindAsync[A] _),
          "async can be derived from asyncF" -> forAll(laws.asyncCanBeDerivedFromAsyncF[A] _),
          "bracket release is called on Completed or Error" -> forAll(
            laws.bracketReleaseIsCalledOnCompletedOrError[A, B] _
          )
        )

        // Activating the tests that detect non-termination only if allowed by Params,
        // because such tests might not be reasonable depending on evaluation model
        if (params.allowNonTerminationLaws)
          default ++ Seq(
            "never is derived from async" -> Prop.lzy(laws.neverIsDerivedFromAsync[A])
          )
        else
          default
      }
    }
}

object AsyncTests {
  def apply[F[_]: Async]: AsyncTests[F] = new AsyncTests[F] {
    def laws = AsyncLaws[F]
  }
}
