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
package laws

import cats.effect.kernel.{MonadCancel, Resource}
import cats.effect.kernel.testkit.{pure, OutcomeGenerators, PureConcGenerators, TestInstances}
import cats.laws.discipline.arbitrary._
import cats.syntax.all._

import org.scalacheck.{Cogen, Prop}
import org.specs2.mutable._
import org.typelevel.discipline.specs2.mutable.Discipline

class ResourcePureConcSpec
    extends Specification
    with Discipline
    with BaseSpec
    with TestInstances {
  import PureConcGenerators._
  import OutcomeGenerators._
  import pure._

  implicit def exec(sbool: Resource[PureConc[Throwable, *], Boolean]): Prop =
    Prop(
      pure
        .run(sbool.use(_.pure[PureConc[Throwable, *]]))
        .fold(false, _ => false, fb => fb.fold(false)(identity))
    )

  implicit def cogenForResource[F[_], A](
      implicit C: Cogen[F[(A, F[Unit])]],
      F: MonadCancel[F, Throwable]): Cogen[Resource[F, A]] =
    C.contramap(_.allocated)

  checkAll(
    "Resource[TimeT[PureConc]]",
    GenSpawnTests[Resource[PureConc[Throwable, *], *], Throwable].spawn[Int, Int, Int]
  )
}
