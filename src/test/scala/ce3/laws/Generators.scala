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

import cats.implicits._

import org.scalacheck.{Arbitrary, Cogen, Gen}, Arbitrary.arbitrary
import cats.~>

class Generators[F[_], E: Arbitrary: Cogen](
  run: F ~> Outcome[Option, E, *]
)(implicit F: ConcurrentBracket[F, E]) {
  import Generators._

  def genF[A: Arbitrary: Cogen](depth: Int): Gen[F[A]] = {
    if (depth > 10) {
      Gen.frequency(
        1 -> genPure[A],
        1 -> genRaiseError[A],
        1 -> genCanceled[A],
        1 -> genCede.flatMap(pc => arbitrary[A].map(pc.as(_))),
        1 -> Gen.delay(genNever))
    } else {
      Gen.frequency(
        1 -> genPure[A],
        1 -> genRaiseError[A],
        1 -> genCanceled[A],
        1 -> genCede.flatMap(pc => arbitrary[A].map(pc.as(_))),
        1 -> Gen.delay(genBracketCase[A](depth)),
        1 -> Gen.delay(genUncancelable[A](depth)),
        1 -> Gen.delay(genHandleErrorWith[A](depth)),
        1 -> Gen.delay(genNever),
        1 -> Gen.delay(genRacePair[A](depth)),
        1 -> Gen.delay(genStart[A](depth)),
        1 -> Gen.delay(genJoin[A](depth)),
        1 -> Gen.delay(genFlatMap[A](depth)))
    }
  }

  def genPure[A: Arbitrary]: Gen[F[A]] =
    arbitrary[A].map(_.pure[F[?]])

  def genRaiseError[A]: Gen[F[A]] =
    arbitrary[E].map(F.raiseError[A](_))

  def genHandleErrorWith[A: Arbitrary: Cogen](depth: Int): Gen[F[A]] = {
    implicit def arbPureConc[A2: Arbitrary: Cogen]: Arbitrary[F[A2]] =
      Arbitrary(genF[A2](depth + 1))

    for {
      fa <- genF[A](depth + 1)
      f <- arbitrary[E => F[A]]
    } yield F.handleErrorWith(fa)(f)
  }

  def genBracketCase[A: Arbitrary: Cogen](depth: Int): Gen[F[A]] = {
    implicit def arbPureConc[A2: Arbitrary: Cogen]: Arbitrary[F[A2]] =
      Arbitrary(genF[A2](depth + 1))

    for {
      acquire <- genF[A](depth + 1)
      use <- arbitrary[A => F[A]]
      release <- arbitrary[(A, Outcome[F, E, A]) => F[Unit]]
    } yield F.bracketCase(acquire)(use)(release)
  }

  // TODO we can't really use poll :-( since we can't Cogen FunctionK
  def genUncancelable[A: Arbitrary: Cogen](depth: Int): Gen[F[A]] =
    genF[A](depth).map(pc => F.uncancelable(_ => pc))

  def genCanceled[A: Arbitrary]: Gen[F[A]] =
    arbitrary[A].map(F.canceled.as(_))

  def genCede: Gen[F[Unit]] =
    F.cede

  def genNever[A]: Gen[F[A]] =
    F.never[A]

  def genStart[A: Arbitrary](depth: Int): Gen[F[A]] =
    genF[Unit](depth).flatMap(pc => arbitrary[A].map(a => F.start(pc).as(a)))

  def genJoin[A: Arbitrary: Cogen](depth: Int): Gen[F[A]] =
    for {
      fiber <- genF[A](depth)
      cont <- genF[Unit](depth)
      a <- arbitrary[A]
    } yield F.start(fiber).flatMap(f => cont >> f.join).as(a)

  def genRacePair[A: Arbitrary: Cogen](depth: Int): Gen[F[A]] =
    for {
      fa <- genF[A](depth + 1)
      fb <- genF[A](depth + 1)

      cancel <- arbitrary[Boolean]

      back = F.racePair(fa, fb) flatMap {
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

  def genFlatMap[A: Arbitrary: Cogen](depth: Int): Gen[F[A]] = {
    implicit val arbPureConc: Arbitrary[F[A]] =
      Arbitrary(genF[A](depth + 1))

    for {
      pc <- genF[A](depth + 1)
      f <- arbitrary[A => F[A]]
    } yield pc.flatMap(f)
  }

  implicit def cogenPureConc[A: Cogen]: Cogen[F[A]] =
    Cogen[Outcome[Option, E, A]].contramap(run(_))
}

object Generators {

  implicit def arbExitCase[F[_], E: Arbitrary, A](implicit A: Arbitrary[F[A]]): Arbitrary[Outcome[F, E, A]] =
    Arbitrary(genExitCase[F, E, A])

  def genExitCase[F[_], E: Arbitrary, A](implicit A: Arbitrary[F[A]]): Gen[Outcome[F, E, A]] =
    Gen.oneOf(
      Gen.const(Outcome.Canceled),
      Arbitrary.arbitrary[E].map(Outcome.Errored(_)),
      Arbitrary.arbitrary[F[A]].map(Outcome.Completed(_)))

  implicit def cogenExitCase[F[_], E: Cogen, A](implicit A: Cogen[F[A]]): Cogen[Outcome[F, E, A]] = Cogen[Option[Either[E, F[A]]]].contramap {
    case Outcome.Canceled => None
    case Outcome.Completed(fa) => Some(Right(fa))
    case Outcome.Errored(e) => Some(Left(e))
  }
}
