/*
 * Copyright 2017 Typelevel
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

import org.scalacheck._

import scala.concurrent.Future
import scala.util.Either

object Generators {
  import Arbitrary._

  implicit def arbIO[A: Arbitrary: Cogen]: Arbitrary[IO[A]] =
    Arbitrary(Gen.delay(genIO[A]))

  def genIO[A: Arbitrary: Cogen]: Gen[IO[A]] = {
    Gen.frequency(
      5 -> genPure[A],
      5 -> genApply[A],
      1 -> genFail[A],
      5 -> genAsync[A],
      5 -> genNestedAsync[A],
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
    arbitrary[A].map(IO.pure)

  def genApply[A: Arbitrary]: Gen[IO[A]] =
    arbitrary[A].map(IO.apply(_))

  def genFail[A]: Gen[IO[A]] =
    arbitrary[Throwable].map(IO.raiseError)

  def genAsync[A: Arbitrary]: Gen[IO[A]] =
    arbitrary[(Either[Throwable, A] => Unit) => Unit].map(IO.async)

  def genNestedAsync[A: Arbitrary: Cogen]: Gen[IO[A]] =
    arbitrary[(Either[Throwable, IO[A]] => Unit) => Unit]
      .map(k => IO.async(k).flatMap(x => x))

  def genBindSuspend[A: Arbitrary: Cogen]: Gen[IO[A]] =
    arbitrary[A].map(IO.apply(_).flatMap(IO.pure))

  def genFlatMap[A: Arbitrary: Cogen]: Gen[IO[A]] =
    for {
      ioa <- arbitrary[IO[A]]
      f <- arbitrary[A => IO[A]]
    } yield ioa.flatMap(f)

  implicit def cogenIO[A](implicit cgfa: Cogen[Future[A]]): Cogen[IO[A]] =
    cgfa.contramap((ioa: IO[A]) => ioa.unsafeToFuture)
}
