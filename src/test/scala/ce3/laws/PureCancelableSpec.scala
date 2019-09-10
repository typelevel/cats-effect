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
  import PureIO.concurrentB

  checkAll(
    "PureIO",
    ConcurrentBracketTests[PureIO[Int, ?], Int].concurrentBracket[Int, Int, Int])

  implicit def prettyFromShow[A: Show](a: A): Pretty =
    Pretty.prettyString(a.show)

  implicit def arbDispatch: Arbitrary[Dispatch] = Arbitrary(genDispatch)

  def genDispatch: Gen[Dispatch] = for {
    ordinal <- Gen.posNum[Int]
    numCanceled <- Gen.chooseNum[Int](0, 20)
    canceled <- Gen.listOfN(numCanceled, Gen.chooseNum[Int](0, ordinal))
  } yield Dispatch(ordinal, canceled.toSet)

  implicit def cogenDispatch: Cogen[Dispatch] =
    Cogen.tuple2[Int, List[Int]] contramap { (d: Dispatch) =>
      (d.ordinal, d.canceled.toList)
    }

  implicit def arbEitherK[F[_], G[_], A](implicit efa: Arbitrary[Either[F[A], G[A]]]): Arbitrary[EitherK[F, G, A]] =
    Arbitrary(efa.map(EitherK(_)))

  def genStateFDispatch[A: Arbitrary]: Gen[StateF[Dispatch, A]] = ???

  implicit def arbStateFDispatch[A: Arbitrary]: Arbitrary[StateF[Dispatch, A]] =
    Arbitrary(genStateFDispatch[A])

  implicit def arbExitCase[E: Arbitrary, A: Arbitrary]: Arbitrary[ExitCase[E, A]] =
    Arbitrary(genExitCase[E, A])

  def genExitCase[E: Arbitrary, A: Arbitrary]: Gen[ExitCase[E, A]] =
    Gen.oneOf(
      Gen.const(ExitCase.Canceled),
      Arbitrary.arbitrary[E].map(ExitCase.Errored(_)),
      Arbitrary.arbitrary[A].map(ExitCase.Completed(_)))

  // copied from FreeSuite
  def headOptionU = λ[List ~> Option](_.headOption)

  private def freeGen[F[_], A](maxDepth: Int)(implicit F: Arbitrary[F[A]], A: Arbitrary[A]): Gen[Free[F, A]] = {
    val noFlatMapped = Gen.oneOf(A.arbitrary.map(Free.pure[F, A]), F.arbitrary.map(Free.liftF[F, A]))

    val nextDepth = Gen.chooseNum(1, math.max(1, maxDepth - 1))

    def withFlatMapped =
      for {
        fDepth <- nextDepth
        freeDepth <- nextDepth
        f <- Arbitrary.arbFunction1[A, Free[F, A]](Arbitrary(freeGen[F, A](fDepth)), Cogen[Unit].contramap(_ => ())).arbitrary
        freeFA <- freeGen[F, A](freeDepth)
      } yield freeFA.flatMap(f)

    if (maxDepth <= 1) noFlatMapped
    else Gen.oneOf(noFlatMapped, withFlatMapped)
  }

  implicit def freeArbitrary[F[_], A](implicit F: Arbitrary[F[A]], A: Arbitrary[A]): Arbitrary[Free[F, A]] =
    Arbitrary(freeGen[F, A](4))

  implicit def freeEq[S[_]: Monad, A](implicit SA: Eq[S[A]]): Eq[Free[S, A]] =
    Eq.by(_.runM(identity))

  implicit def freeShow[S[_]: Monad, A](implicit SA: Show[S[A]]): Show[Free[S, A]] =
    SA.contramap((fsa: Free[S, A]) => fsa.runM(identity))

  def beEqv[A: Eq: Show](expect: A): Matcher[A] = be_===[A](expect)

  def be_===[A: Eq: Show](expect: A): Matcher[A] = (result: A) =>
    (result === expect, s"${result.show} === ${expect.show}", s"${result.show} !== ${expect.show}")
}
