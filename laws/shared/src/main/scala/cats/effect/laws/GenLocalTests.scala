/*
 * Copyright 2020-2021 Typelevel
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

import cats.Eq
import cats.effect.kernel.GenLocal

import org.scalacheck._, Prop.forAll
import org.scalacheck.util.Pretty

import org.typelevel.discipline.Laws

trait GenLocalTests[F[_], E] extends Laws {

  val laws: GenLocalLaws[F, E]

  def local[A: Arbitrary](
      implicit eqFA: Eq[F[A]],
      eqFU: Eq[F[Unit]],
      faPP: F[A] => Pretty
  ): RuleSet = {

    new RuleSet {
      val name = "fiberLocal"
      val bases = Nil
      val parents = Seq()

      val props = Seq(
        "default get" -> forAll(laws.defaultGet[A] _),
        "set then get" -> forAll(laws.setThenGet[A] _),
        "set is idempotent" -> forAll(laws.setIsIdempotent[A] _),
        "copy on fork" -> forAll(laws.copyOnFork[A] _),
        "modification in parent" -> forAll(laws.modificationInParent[A] _),
        "modification in child" -> forAll(laws.modificationInChild[A] _),
        "allocate in child" -> forAll(laws.allocateInChild[A] _)
      )
    }
  }
}

object GenLocalTests {
  def apply[F[_], E](implicit F0: GenLocal[F, E]): GenLocalTests[F, E] =
    new GenLocalTests[F, E] {
      val laws = GenLocalLaws[F, E]
    }
}
