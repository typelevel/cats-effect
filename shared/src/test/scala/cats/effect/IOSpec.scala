/*
 * Copyright 2017 Daniel Spiewak
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

import cats.implicits._
import cats.kernel._
import cats.kernel.laws.GroupLaws
import cats.laws.discipline.MonadErrorTests

import org.scalatest._
import org.typelevel.discipline.scalatest.Discipline

class IOSpec extends FunSuite with Discipline {
  import Generators._

  checkAll("IO", MonadErrorTests[IO, Throwable].monad[Int, Int, Int])
  checkAll("IO", MonadErrorTests[IO, Throwable].monadError[Int, Int, Int])
  checkAll("IO", GroupLaws[IO[Int]].monoid)

  implicit def eqIO[A: Eq]: Eq[IO[A]] = Eq by { ioa =>
    var result: Option[Either[Throwable, A]] = None

    ioa.runAsync(e => IO { result = Some(e) }).unsafeRunSync

    result
  }

  implicit def eqThrowable: Eq[Throwable] = Eq.fromUniversalEquals[Throwable]
}
