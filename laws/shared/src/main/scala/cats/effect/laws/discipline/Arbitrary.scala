/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

package cats
package effect
package laws
package discipline

import cats.Eval
import cats.effect.IO.Par
import cats.effect.internals.IORunLoop
import org.scalacheck.Arbitrary.{arbitrary => getArbitrary}
import org.scalacheck._
import scala.util.Either
import cats.laws.discipline.arbitrary._

object arbitrary {
  implicit def catsEffectLawsArbitraryForIO[A: Arbitrary: Cogen]: Arbitrary[IO[A]] =
    Arbitrary(Gen.delay(genIO[A]))

  implicit def catsEffectLawsArbitraryForEvalEff[A: Arbitrary]: Arbitrary[EvalEff[A]] =
    Arbitrary(catsLawsArbitraryForEitherT[Eval, Throwable, A].arbitrary.map(e => EvalEff(e.value)))

  implicit def catsEffectLawsArbitraryForIOParallel[A: Arbitrary: Cogen]: Arbitrary[IO.Par[A]] =
    Arbitrary(catsEffectLawsArbitraryForIO[A].arbitrary.map(Par.apply))

  def genIO[A: Arbitrary: Cogen]: Gen[IO[A]] = {
    Gen.frequency(
      5 -> genPure[A],
      5 -> genApply[A],
      1 -> genFail[A],
      5 -> genAsync[A],
      5 -> genNestedAsync[A],
      5 -> getMapOne[A],
      5 -> getMapTwo[A],
      10 -> genFlatMap[A])
  }

  def genSyncIO[A: Arbitrary: Cogen]: Gen[IO[A]] = {
    Gen.frequency(
      5 -> genPure[A],
      5 -> genApply[A],
      1 -> genFail[A],
      5 -> genBindSuspend[A])
  }

  def genPure[A: Arbitrary]: Gen[IO[A]] =
    getArbitrary[A].map(IO.pure)

  def genApply[A: Arbitrary]: Gen[IO[A]] =
    getArbitrary[A].map(IO.apply(_))

  def genFail[A]: Gen[IO[A]] =
    getArbitrary[Throwable].map(IO.raiseError)

  def genAsync[A: Arbitrary]: Gen[IO[A]] =
    getArbitrary[(Either[Throwable, A] => Unit) => Unit].map(IO.async)

  def genNestedAsync[A: Arbitrary: Cogen]: Gen[IO[A]] =
    getArbitrary[(Either[Throwable, IO[A]] => Unit) => Unit]
      .map(k => IO.async(k).flatMap(x => x))

  def genBindSuspend[A: Arbitrary: Cogen]: Gen[IO[A]] =
    getArbitrary[A].map(IO.apply(_).flatMap(IO.pure))

  def genFlatMap[A: Arbitrary: Cogen]: Gen[IO[A]] =
    for {
      ioa <- getArbitrary[IO[A]]
      f <- getArbitrary[A => IO[A]]
    } yield ioa.flatMap(f)

  def getMapOne[A: Arbitrary: Cogen]: Gen[IO[A]] =
    for {
      ioa <- getArbitrary[IO[A]]
      f <- getArbitrary[A => A]
    } yield ioa.map(f)

  def getMapTwo[A: Arbitrary: Cogen]: Gen[IO[A]] =
    for {
      ioa <- getArbitrary[IO[A]]
      f1 <- getArbitrary[A => A]
      f2 <- getArbitrary[A => A]
    } yield ioa.map(f1).map(f2)

  implicit def catsEffectLawsCogenForIO[A](implicit cga: Cogen[A]): Cogen[IO[A]] =
    Cogen { (seed, io) =>
      IORunLoop.step(io) match {
        case IO.Pure(a) => cga.perturb(seed, a)
        case _ => seed
      }
    }
}
