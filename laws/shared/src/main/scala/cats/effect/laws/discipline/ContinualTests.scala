/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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
package laws
package discipline

import cats.Eq
import cats.laws.discipline._
import org.scalacheck.Arbitrary
import org.scalacheck.Prop.forAll
import org.typelevel.discipline.Laws

trait ContinualTests[F[_]] extends Laws {
  def laws: ContinualLaws[F]

  def sync[A: Arbitrary: Eq](
    implicit
    ArbFA: Arbitrary[F[A]],
    EqFA: Eq[F[A]],
    params: Parameters): RuleSet = {

    new RuleSet {
      val name = "continual.sync"
      val bases = Nil
      val parents = Nil
      val props = Seq(
        "continual mirrors source" -> forAll(laws.continualMirrorsSource[A] _),
        "onCancelRaiseError mirrors source" -> forAll(laws.onCancelRaiseErrorMirrorsSource[A] _)
      )
    }
  }

  def concurrent[A: Arbitrary: Eq](
    implicit
    ArbFA: Arbitrary[F[A]],
    EqFA: Eq[F[A]],
    F: Concurrent[F],
    params: Parameters): RuleSet = {

    new RuleSet {
      val name = "continual.concurrent"
      val bases = Nil
      val parents = Seq(sync[A])
      val props = Seq(
        "continual cancelation model" -> forAll(laws.continualCancelationModel[A] _),
        "onCancelRaiseError terminates on cancel" -> forAll(laws.onCancelRaiseErrorTerminatesOnCancel[A] _),
        "onCancelRaiseError can cancel source" -> forAll(laws.onCancelRaiseErrorCanCancelSource[A] _),
        "onCancelRaiseError resets cancelation flag" -> forAll(laws.onCancelRaiseErrorResetsCancellationFlag[A] _),
        "guaranteeCase terminates on cancel" -> forAll(laws.guaranteeCaseTerminatesOnCancel[A] _),
        "guaranteeCase can cancel source" -> forAll(laws.guaranteeCaseCanCancelSource[A] _),
        "guaranteeCase resets cancelation flag" -> forAll(laws.guaranteeCaseResetsCancellationFlag[A] _)
      )
    }
  }
}

object ContinualTests {
  def apply[F[_]: Continual]: ContinualTests[F] = new ContinualTests[F] {
    def laws = ContinualLaws[F]
  }
}
