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
import cats.effect.laws.discipline.EffectTests

import org.scalatest._
import org.typelevel.discipline.scalatest.Discipline

class IOTests extends FunSuite with Matchers with Discipline {
  import Generators._

  checkAll("IO", EffectTests[IO].effect[Int, Int, Int])
  checkAll("IO", GroupLaws[IO[Int]].monoid)

  test("defer evaluation until run") {
    var run = false
    val ioa = IO { run = true }
    run shouldEqual false
    ioa.unsafeRunSync()
    run shouldEqual true
  }

  test("catch exceptions within main block") {
    case object Foo extends Exception

    val ioa = IO { throw Foo }

    ioa.attempt.unsafeRunSync() should matchPattern {
      case Left(Foo) => ()
    }
  }

  test("evaluate ensuring actions") {
    case object Foo extends Exception

    var run = false
    val ioa = IO { throw Foo } ensuring IO { run = true }

    ioa.attempt.unsafeRunSync() should matchPattern {
      case Left(Foo) => ()
    }

    run shouldEqual true
  }

  test("prioritize thrown exceptions from within ensuring") {
    case object Foo extends Exception
    case object Bar extends Exception

    val ioa = IO { throw Foo } ensuring IO.fail(Bar)

    ioa.attempt.unsafeRunSync() should matchPattern {
      case Left(Bar) => ()
    }
  }

  test("provide stack safety on repeated attempts") {
    val result = (0 until 10000).foldLeft(IO(0)) { (acc, _) =>
      acc.attempt.map(_ => 0)
    }

    result.unsafeRunSync() shouldEqual 0
  }

  implicit def eqIO[A: Eq]: Eq[IO[A]] = Eq by { ioa =>
    var result: Option[Either[Throwable, A]] = None

    ioa.runAsync(e => IO { result = Some(e) }).unsafeRunSync()

    result
  }

  implicit def eqThrowable: Eq[Throwable] = Eq.fromUniversalEquals[Throwable]
}
