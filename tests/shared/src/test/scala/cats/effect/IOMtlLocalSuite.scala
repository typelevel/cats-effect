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

package cats
package effect

import cats.data.OptionT
import cats.laws.discipline.arbitrary._
import cats.mtl.Local
import cats.mtl.laws.discipline._

import java.util.concurrent.CancellationException

import munit.DisciplineSuite

class IOMtlLocalSuite extends BaseSuite with DisciplineSuite {

  implicit val ticker: Ticker = Ticker()

  def mkLocal[F[_]](instance: IO[Local[F, Int]]): Local[F, Int] =
    // Don't try this at home
    unsafeRun(instance).fold(
      throw new CancellationException("canceled"),
      throw _,
      _.get
    )

  locally {
    implicit val local: Local[IO, Int] = mkLocal(IO.local(0))
    checkAll("Local[IO, Int]", LocalTests[IO, Int].local[Int, Int])
  }

  locally {
    implicit val local: Local[OptionT[IO, *], Int] =
      mkLocal(IOLocal(0).map(_.asLocal[OptionT[IO, *]]))
    checkAll("Local[OptionT[IO, *], Int]", LocalTests[OptionT[IO, *], Int].local[Int, Int])
  }
}
