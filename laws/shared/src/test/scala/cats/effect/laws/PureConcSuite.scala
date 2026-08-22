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

import cats.effect.kernel.testkit.{pure, OutcomeGenerators, PureConcGenerators, TimeT}
import cats.effect.kernel.testkit.TimeT._
import cats.effect.kernel.testkit.pure._
import cats.laws.discipline.arbitrary._

import org.scalacheck.Prop

import scala.concurrent.duration._

import munit.DisciplineSuite

class PureConcSuite extends DisciplineSuite with BaseSuite {
  import PureConcGenerators._
  import OutcomeGenerators._

  implicit def exec(fb: TimeT[PureConc[Int, *], Boolean]): Prop =
    Prop(pure.run(TimeT.run(fb)).fold(false, _ => false, _.getOrElse(false)))

  {
    import cats.effect.kernel.{GenConcurrent, Outcome}
    import cats.effect.kernel.implicits._
    import cats.syntax.all._

    type F[A] = PureConc[Int, A]
    val F = GenConcurrent[F]

    test("short-circuit on error") {
      assert(
        pure.run((F.never[Unit], F.raiseError[Unit](42)).parTupled) === Outcome.Errored(42))
      assertEquals(
        pure.run((F.raiseError[Unit](42), F.never[Unit]).parTupled),
        Outcome.Errored[Option, Int, (Unit, Unit)](42))
    }

    test("short-circuit on canceled") {
      assert(
        pure.run((F.never[Unit], F.canceled).parTupled.start.flatMap(_.join)) === Outcome
          .Succeeded(Some(Outcome.canceled[F, Int, (Unit, Unit)])))
      assert(
        pure.run((F.canceled, F.never[Unit]).parTupled.start.flatMap(_.join)) === Outcome
          .Succeeded(Some(Outcome.canceled[F, Int, (Unit, Unit)])))
    }

    test("not run forever on chained product") {
      import cats.effect.kernel.Par.ParallelF

      val fa: F[String] = F.pure("a")
      val fb: F[String] = F.pure("b")
      val fc: F[Unit] = F.raiseError[Unit](42)
      assert(
        pure.run(ParallelF.value(
          ParallelF(fa).product(ParallelF(fb)).product(ParallelF(fc)))) === Outcome.Errored(42))
    }

    test("ignore unmasking in finalizers") {
      val fa = F.uncancelable { poll => F.onCancel(poll(F.unit), poll(F.unit)) }

      pure.run(fa.start.flatMap(_.cancel))
    }
  }

  checkAll(
    "TimeT[PureConc]",
    GenTemporalTests[TimeT[PureConc[Int, *], *], Int].temporal[Int, Int, Int](10.millis)
  )
}
