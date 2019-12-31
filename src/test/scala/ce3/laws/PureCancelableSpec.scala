/*
 * Copyright 2019 Daniel Spiewak
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

import cats.{~>, ApplicativeError, Eq, FlatMap, Monad, Monoid, Show}
import cats.data.{EitherK, StateT}
import cats.free.Free
import cats.implicits._

import playground._

import org.scalacheck.{Arbitrary, Cogen, Gen}
import org.scalacheck.util.Pretty

import org.specs2.matcher.Matcher
import org.specs2.mutable._

import org.typelevel.discipline.specs2.mutable.Discipline

class PureCancelableSpec extends Specification with Discipline {

  // TODO
  // - Arbitrary[Kleisli]
  // - Arbitrary[FreeT]
  // - Cogen[Kleisli]
  // - Cogen[FreeT]

  checkAll(
    "PureConc",
    BracketTests[PureConc[Int, ?], Int].bracket[Int, Int, Int])

  /*checkAll(
    "PureConc",
    ConcurrentBracketTests[PureConc[Int, ?], Int].concurrentBracket[Int, Int, Int])*/

  implicit def prettyFromShow[A: Show](a: A): Pretty =
    Pretty.prettyString(a.show)

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

  // copied from FreeSuite
  def headOptionU = λ[List ~> Option](_.headOption)

  implicit def pureConcEq[E: Eq, A: Eq]: Eq[PureConc[E, A]] = Eq.by(run(_))

  def beEqv[A: Eq: Show](expect: A): Matcher[A] = be_===[A](expect)

  def be_===[A: Eq: Show](expect: A): Matcher[A] = (result: A) =>
    (result === expect, s"${result.show} === ${expect.show}", s"${result.show} !== ${expect.show}")
}
