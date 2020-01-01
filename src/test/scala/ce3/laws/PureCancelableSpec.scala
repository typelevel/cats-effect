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

import cats.{~>, Applicative, ApplicativeError, Eq, FlatMap, Monad, Monoid, Show}
import cats.data.{EitherK, StateT}
import cats.free.FreeT
import cats.implicits._

import playground._

import org.scalacheck.{Arbitrary, Cogen, Gen}, Arbitrary.arbitrary
import org.scalacheck.util.Pretty

import org.specs2.matcher.Matcher
import org.specs2.mutable._

import org.typelevel.discipline.specs2.mutable.Discipline

class PureCancelableSpec extends Specification with Discipline {

  def F[E] = ConcurrentBracket[PureConc[E, ?], E]

  checkAll(
    "PureConc",
    BracketTests[PureConc[Int, ?], Int].bracket[Int, Int, Int])

  /*checkAll(
    "PureConc",
    ConcurrentBracketTests[PureConc[Int, ?], Int].concurrentBracket[Int, Int, Int])*/

  implicit def prettyFromShow[A: Show](a: A): Pretty =
    Pretty.prettyString(a.show)

  implicit def arbPureConc[E: Arbitrary: Cogen, A: Arbitrary: Cogen]: Arbitrary[PureConc[E, A]] =
    Arbitrary(genPureConc[E, A])

  def genPureConc[E: Arbitrary: Cogen, A: Arbitrary: Cogen]: Gen[PureConc[E, A]] =
    Gen.frequency(
      1 -> genPure[E, A],
      1 -> genRaiseError[E, A],
      1 -> genCanceled[E, A],
      1 -> genCede[E].flatMap(pc => arbitrary[A].map(pc.as(_))),
      1 -> Gen.delay(genBracketCase[E, A]),
      1 -> Gen.delay(genUncancelable[E, A]),
      1 -> Gen.delay(genHandleErrorWith[E, A]),
      1 -> Gen.delay(genNever),
      1 -> Gen.delay(genRacePair[E, A]),
      1 -> Gen.delay(genStart[E, A]),
      1 -> Gen.delay(genFlatMap[E, A]))

  def genPure[E, A: Arbitrary]: Gen[PureConc[E, A]] =
    arbitrary[A].map(_.pure[PureConc[E, ?]])

  def genRaiseError[E: Arbitrary, A]: Gen[PureConc[E, A]] =
    arbitrary[E].map(F[E].raiseError[A](_))

  def genHandleErrorWith[E: Arbitrary: Cogen, A: Arbitrary: Cogen]: Gen[PureConc[E, A]] =
    for {
      fa <- genPureConc[E, A]
      f <- arbitrary[E => PureConc[E, A]]
    } yield F[E].handleErrorWith(fa)(f)

  def genBracketCase[E: Arbitrary: Cogen, A: Arbitrary: Cogen]: Gen[PureConc[E, A]] =
    for {
      acquire <- genPureConc[E, A]
      use <- arbitrary[A => PureConc[E, A]]
      release <- arbitrary[(A, ExitCase[PureConc[E, ?], E, A]) => PureConc[E, Unit]]
    } yield F[E].bracketCase(acquire)(use)(release)

  // TODO we can't really use poll :-( since we can't Cogen FunctionK
  def genUncancelable[E: Arbitrary: Cogen, A: Arbitrary: Cogen]: Gen[PureConc[E, A]] =
    genPureConc[E, A].map(pc => F[E].uncancelable(_ => pc))

  def genCanceled[E, A: Arbitrary]: Gen[PureConc[E, A]] =
    arbitrary[A].map(F[E].canceled(_))

  def genCede[E]: Gen[PureConc[E, Unit]] =
    F[E].cede

  def genNever[E, A]: Gen[PureConc[E, A]] =
    F[E].never[A]

  def genStart[E: Arbitrary: Cogen, A: Arbitrary]: Gen[PureConc[E, A]] =
    genPureConc[E, Unit].flatMap(pc => arbitrary[A].map(a => F[E].start(pc).as(a)))

  def genRacePair[E: Arbitrary: Cogen, A: Arbitrary: Cogen]: Gen[PureConc[E, A]] =
    for {
      fa <- genPureConc[E, A]
      fb <- genPureConc[E, A]

      cancel <- arbitrary[Boolean]

      back = F[E].racePair(fa, fb) flatMap {
        case Left((a, f)) =>
          if (cancel)
            f.cancel.as(a)
          else
            f.join.as(a)

        case Right((f, a)) =>
          if (cancel)
            f.cancel.as(a)
          else
            f.join.as(a)
      }
    } yield back

  def genFlatMap[E: Arbitrary: Cogen, A: Arbitrary: Cogen]: Gen[PureConc[E, A]] =
    for {
      pc <- genPureConc[E, A]
      f <- arbitrary[A => PureConc[E, A]]
    } yield pc.flatMap(f)

  implicit def cogenPureConc[E: Cogen, A: Cogen]: Cogen[PureConc[E, A]] =
    Cogen[ExitCase[Option, E, A]].contramap(run(_))

  implicit def arbExitCase[F[_], E: Arbitrary, A](implicit A: Arbitrary[F[A]]): Arbitrary[ExitCase[F, E, A]] =
    Arbitrary(genExitCase[F, E, A])

  def genExitCase[F[_], E: Arbitrary, A](implicit A: Arbitrary[F[A]]): Gen[ExitCase[F, E, A]] =
    Gen.oneOf(
      Gen.const(ExitCase.Canceled),
      Arbitrary.arbitrary[E].map(ExitCase.Errored(_)),
      Arbitrary.arbitrary[F[A]].map(ExitCase.Completed(_)))

  implicit def cogenExitCase[F[_], E: Cogen, A](implicit A: Cogen[F[A]]): Cogen[ExitCase[F, E, A]] = Cogen[Option[Either[E, F[A]]]].contramap {
    case ExitCase.Canceled => None
    case ExitCase.Completed(fa) => Some(Right(fa))
    case ExitCase.Errored(e) => Some(Left(e))
  }

  implicit def pureConcEq[E: Eq, A: Eq]: Eq[PureConc[E, A]] = Eq.by(run(_))

  def beEqv[A: Eq: Show](expect: A): Matcher[A] = be_===[A](expect)

  def be_===[A: Eq: Show](expect: A): Matcher[A] = (result: A) =>
    (result === expect, s"${result.show} === ${expect.show}", s"${result.show} !== ${expect.show}")
}
