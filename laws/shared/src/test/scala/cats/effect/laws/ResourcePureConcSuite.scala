/*
 * Copyright 2020-2025 Typelevel
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

import cats.CommutativeApplicative
import cats.effect.kernel.{GenConcurrent, MonadCancel, Outcome, ParallelF, Resource}
import cats.effect.kernel.testkit.{pure, OutcomeGenerators, PureConcGenerators, TestInstances}
import cats.laws.discipline.arbitrary._
import cats.syntax.all._

import org.scalacheck.{Cogen, Prop}

import munit.DisciplineSuite

class ResourcePureConcSuite extends DisciplineSuite with BaseSuite with TestInstances {

  import PureConcGenerators._
  import OutcomeGenerators._
  import pure._

  test("preserve masking when forceR discards a pure resource") {
    type F[A] = PureConc[Throwable, A]
    val F = GenConcurrent[F]

    def run(resource: Resource[F, Int]): Outcome[Option, Throwable, Int] =
      pure.run(resource.use(F.pure))

    val target = Resource(F.canceled.as(1 -> F.uncancelable(_ => F.canceled *> F.never[Unit])))
    val forced = Resource.pure[F, Unit](()).forceR(target)
    val expected = Outcome.Succeeded[Option, Throwable, Int](None)

    assertEquals(run(target), expected)
    assertEquals(run(forced), expected)
  }

  test("derive race from racePair for a masked self-canceling resource") {
    type F[A] = PureConc[Throwable, A]
    val F = GenConcurrent[F]
    type R[A] = Resource[F, A]
    val R = GenConcurrent[R]

    val fa = Resource(F.canceled.as(1 -> F.uncancelable(_ => F.canceled *> F.never[Unit])))
    val expected = R.race(fa, R.never[Int])
    val received = R.uncancelable { poll =>
      R.racePair(fa, R.never[Int]).flatMap {
        case Left((outcome, fiber)) =>
          outcome match {
            case Outcome.Succeeded(value) => fiber.cancel *> value.map(_.asLeft[Int])
            case Outcome.Errored(error) => fiber.cancel *> R.raiseError(error)
            case Outcome.Canceled() =>
              (fiber.cancel *> fiber.join).flatMap {
                case Outcome.Succeeded(value) => value.map(_.asRight[Int])
                case Outcome.Errored(error) => R.raiseError(error)
                case Outcome.Canceled() => poll(R.canceled) *> R.never
              }
          }
        case Right((fiber, outcome)) =>
          outcome match {
            case Outcome.Succeeded(value) => fiber.cancel *> value.map(_.asRight[Int])
            case Outcome.Errored(error) => fiber.cancel *> R.raiseError(error)
            case Outcome.Canceled() =>
              (fiber.cancel *> fiber.join).flatMap {
                case Outcome.Succeeded(value) => value.map(_.asLeft[Int])
                case Outcome.Errored(error) => R.raiseError(error)
                case Outcome.Canceled() => poll(R.canceled) *> R.never
              }
          }
      }
    }

    assertEquals(pure.run(expected.use(F.pure)), pure.run(received.use(F.pure)))
  }

  test("ignore release self-cancelation through parallel applicative identity") {
    type F[A] = PureConc[Throwable, A]
    type R[A] = Resource[F, A]
    type P[A] = ParallelF[R, A]

    val F = GenConcurrent[F]
    val P = CommutativeApplicative[P]

    val resource = Resource(F.pure(1 -> F.canceled))
    val identityApplied = ParallelF.value(P.ap(P.pure((i: Int) => i))(ParallelF(resource)))
    val expected = Outcome.Succeeded[Option, Throwable, Int](Some(1))

    assertEquals(pure.run(resource.use(F.pure)), expected)
    assertEquals(pure.run(identityApplied.use(F.pure)), expected)
  }

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
