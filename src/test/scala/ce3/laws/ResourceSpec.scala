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
/* 
class ResourceSpec extends Specification with Discipline with ScalaCheck {
  import Generators._

  def arbOutcomeFEFUniversal[F[_]: Applicative, E, A](
      implicit arbOutcome: Arbitrary[Outcome[F, E, A]]
  ): Arbitrary[Outcome[F, E, _]] =
    Arbitrary(arbOutcome.arbitrary.map(_.widen[Any]))

  def cogenOutcomeFEFUniversal[F[_]: Applicative, E](
      implicit cogenOutcome: Cogen[Outcome[F, E, Unit]]
  ): Cogen[Outcome[F, E, _]] =
    cogenOutcome.contramap(_.void)

  checkAll(
    "Resource", {

      def i[A](implicit a: A): a.type = a

      type F[A] = PureConc[Int, A]

      type RF[A] = Resource[F, A]

      implicit def cogenFinalizer[A](
          implicit arbFA: Arbitrary[F[A]]
      ): Cogen[Outcome[F, Int, _] => F[Unit]] =
        Cogen.function1[Outcome[F, Int, _], F[Unit]](
          arbOutcomeFEFUniversal[F, Int, A],
          Cogen[F[Unit]]
        )

      implicit def arbRFA[A: Arbitrary: Cogen]: Arbitrary[RF[A]] =
        arbResource[F, Outcome[F, Int, *], Int, A](
          i[Arbitrary[A]],
          Cogen[A],
          i[Bracket[F, Int]],
          Arbitrary(Gen.delay(i[Arbitrary[F[RF[A]]]].arbitrary)),
          arbPureConc[Int, (A, Outcome[F, Int, _] => F[Unit])](
            i[Arbitrary[Int]],
            Cogen[Int],
            i[Arbitrary[(A, Outcome[F, Int, _] => F[Unit])]],
            Cogen.tuple2[A, Outcome[F, Int, _] => F[Unit]](
              Cogen[A],
              cogenFinalizer[A]
            )
          )
        )

      implicit def cogenOutcomeFE[E: Cogen]: Cogen[Outcome[F, E, _]] =
        cogenOutcomeFEFUniversal[F, E]

      RegionTests[
        Resource,
        PureConc[Int, *],
        Outcome[PureConc[Int, *], Int, *],
        Int
      ].region[Int, Int, Int]
    }
  )

  implicit def arbPureConc[E: Arbitrary: Cogen, A: Arbitrary: Cogen]
      : Arbitrary[PureConc[E, A]] =
    Arbitrary(genPureConc[E, A](0))

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

  implicit def arbResource[F[_], Case[_], E, A: Arbitrary: Cogen](
      implicit
      bracket: Bracket.Aux[F, E, Case],
      arbEffect: Arbitrary[F[Resource[F, A]]],
      arbAlloc: Arbitrary[F[(A, Case[_] => F[Unit])]]
  ): Arbitrary[Resource[F, A]] = Arbitrary(genResource[F, Case, E, A])

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
 */
