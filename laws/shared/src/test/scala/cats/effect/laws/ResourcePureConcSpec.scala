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

import cats.Applicative
import cats.effect.kernel.{MonadCancel, Resource}
import cats.effect.kernel.testkit.{pure, OutcomeGenerators, PureConcGenerators}, pure._
import cats.laws.discipline.arbitrary._
import cats.kernel.Eq
import cats.syntax.all._

import org.scalacheck.{Arbitrary, Cogen, Gen, Prop}, Arbitrary.arbitrary

import org.specs2.ScalaCheck
import org.specs2.mutable._

import org.typelevel.discipline.specs2.mutable.Discipline

class ResourcePureConcSpec extends Specification with Discipline with ScalaCheck with BaseSpec {
  import PureConcGenerators._
  import OutcomeGenerators._

  implicit def exec(sbool: Resource[PureConc[Throwable, *], Boolean]): Prop =
    Prop(
      pure
        .run(sbool.use(_.pure[PureConc[Throwable, *]]))
        .fold(false, _ => false, fb => fb.fold(false)(identity))
    )

  //////////// Copy-pasted code
  implicit def arbitraryResource[F[_], A](
      implicit F: Applicative[F],
      AFA: Arbitrary[F[A]],
      AFU: Arbitrary[F[Unit]],
      AA: Arbitrary[A]
  ): Arbitrary[Resource[F, A]] =
    Arbitrary(Gen.delay(genResource[F, A]))

  // Consider improving this a strategy similar to Generators.
  // Doesn't include interruptible resources
  def genResource[F[_], A](
      implicit F: Applicative[F],
      AFA: Arbitrary[F[A]],
      AFU: Arbitrary[F[Unit]],
      AA: Arbitrary[A]
  ): Gen[Resource[F, A]] = {
    def genAllocate: Gen[Resource[F, A]] =
      for {
        alloc <- arbitrary[F[A]]
        dispose <- arbitrary[F[Unit]]
      } yield Resource(alloc.map(a => a -> dispose))

    def genBind: Gen[Resource[F, A]] =
      genAllocate.map(_.flatMap(a => Resource.pure[F, A](a)))

    def genEval: Gen[Resource[F, A]] =
      arbitrary[F[A]].map(Resource.eval)

    def genPure: Gen[Resource[F, A]] =
      arbitrary[A].map(Resource.pure)

    Gen.frequency(
      5 -> genAllocate,
      1 -> genBind,
      1 -> genEval,
      1 -> genPure
    )
  }

  implicit def eqResource[F[_], A](
      implicit E: Eq[F[A]],
      F: MonadCancel[F, Throwable]): Eq[Resource[F, A]] =
    new Eq[Resource[F, A]] {
      def eqv(x: Resource[F, A], y: Resource[F, A]): Boolean =
        E.eqv(x.use(F.pure), y.use(F.pure))
    }
  //////////// End of copy-pasted code

  implicit def cogenForResource[F[_], A](
      implicit C: Cogen[F[(A, F[Unit])]],
      F: MonadCancel[F, Throwable]): Cogen[Resource[F, A]] =
    C.contramap(_.allocated)

  checkAll(
    "Resource[TimeT[PureConc]]",
    GenSpawnTests[Resource[PureConc[Throwable, *], *], Throwable].spawn[Int, Int, Int]
  )
}
