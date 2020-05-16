/*
 * Copyright 2020 Typelevel
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

import cats.{
  ~>,
  Applicative,
  ApplicativeError,
  Eq,
  FlatMap,
  Monad,
  Monoid,
  Show
}
import cats.data.{EitherK, StateT}
import cats.free.FreeT
import cats.implicits._

import coop.ThreadT

import org.scalacheck.{Arbitrary, Cogen, Gen}, Arbitrary.arbitrary
import org.scalacheck.util.Pretty

import org.specs2.ScalaCheck
import org.specs2.matcher.Matcher
import org.specs2.mutable._

import playground._
import org.typelevel.discipline.specs2.mutable.Discipline
import cats.laws.discipline.SemigroupalTests.Isomorphisms


trait CogenK[F[_]] {
  def cogen[A: Cogen]: Cogen[F[A]]
}

object ResourceGenerators {

  def resourceGenerators[F[_], Case0[_], E: Arbitrary: Cogen](
    implicit
    bracket: Bracket.Aux[F, E, Case0],
    genKF: GenK[F],
    cogenCase0: CogenK[Case0]
  ): RegionGenerators[Resource, F, E] = new RegionGenerators[Resource, F, E] {
    override type Case[A] = Case0[A]

    val arbitraryE: Arbitrary[E] = implicitly[Arbitrary[E]]
    val cogenE: Cogen[E] = Cogen[E]
    def cogenCase[A: Cogen]: Cogen[Case[A]] = cogenCase0.cogen[A]
    val F: Region.Aux[Resource,F,E, Case] = Resource.regionForResource[F, E](bracket)
    val GenKF: GenK[F] = genKF
  }

  import PureConcGenerators.cogenKPureConc
  import PureConcGenerators.genKPureConc
  import OutcomeGenerators.cogenKOutcome

  implicit def pureConcResourceArb[A: Arbitrary: Cogen]: Arbitrary[Resource[PureConc[Int, *], A]] =
    Arbitrary {
      resourceGenerators[PureConc[Int, *], Outcome[PureConc[Int, *], Int, *], Int].generators[A]
    }
}

class ResourceSpec extends Specification with Discipline with ScalaCheck {
  import ResourceGenerators._
  import PureConcGenerators._

  implicit def Cogen: Cogen[Outcome[PureConc[Int, *], Int, _]] =
    OutcomeGenerators.cogenOutcome[PureConc[Int, *], Int, Unit].contramap(_.void)

  checkAll(
    "Resource", 
      RegionTests[
        Resource,
        PureConc[Int, *],
        Outcome[PureConc[Int, *], Int, *],
        Int
      ].region[Int, Int, Int]
  )

  implicit def prettyFromShow[A: Show](a: A): Pretty =
    Pretty.prettyString(a.show)

  def beEqv[A: Eq: Show](expect: A): Matcher[A] = be_===[A](expect)

  def be_===[A: Eq: Show](expect: A): Matcher[A] =
    (result: A) =>
      (
        result === expect,
        s"${result.show} === ${expect.show}",
        s"${result.show} !== ${expect.show}"
      )

  implicit def cogenResource[F[_], E, A](
      implicit
      bracket: Bracket[F, E],
      cogenF: Cogen[F[Unit]]
  ): Cogen[Resource[F, A]] = cogenF.contramap(_.used)

  implicit def eqResource[F[_]: Bracket[*[_], E], E, A](
      implicit
      eqFUnit: Eq[F[Unit]]
      /*  exhaustiveUse: ExhaustiveCheck[A => F[B]], */
      // eqFListB: Eq[F[List[B]]]
  ): Eq[Resource[F, A]] =
    Eq.by(
      _.used
    ) // { resource => exhaustiveUse.allValues.traverse(resource.use(_)) }

}

object Demo extends App {

  def prog[F[_]: ConcurrentBracket[*[_], E], E, A, B](
      a: A,
      b: B,
      e: E
  ): F[B] = {
    val F = ConcurrentBracket[F, E]

    val R = implicitly[Region[Resource, F, E]]

    Resource
      .make(F.pure(b))(b => F.raiseError[Unit](e))
      .use(a => F.pure(a))
  }

  // Completed(Some(hello))
  // │
  // ├ Notify 0x6404F418
  // ├ Notify 0x6404F418
  // ├ Notify 0x6404F418
  // ├ Notify 0x6404F418
  // ├ Notify 0x6404F418
  // ├ Notify 0x2794EAB6
  // ╰ Pure Completed(hello)
  //todo: shouldn't this raise an exception in the background? How does PureConc handle failures in finalizers?
  println {
    prog[PureConc[Int, *], Int, Int, String](42, "hello", -5).show
  }
}
