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

import cats.effect.laws.discipline.arbitrary._
import cats.kernel.laws.discipline.MonoidTests
import cats.laws._
import cats.laws.discipline._
import cats.laws.discipline.arbitrary._
import cats.implicits._

class ResourceTests extends BaseTestsSuite {
  checkAllAsync("Resource[IO, ?]", implicit ec => MonadErrorTests[Resource[IO, ?], Throwable].monadError[Int, Int, Int])
  checkAllAsync("Resource[IO, Int]", implicit ec => MonoidTests[Resource[IO, Int]].monoid)
  checkAllAsync("Resource[IO, ?]", implicit ec => SemigroupKTests[Resource[IO, ?]].semigroupK[Int])

  testAsync("Resource.make is equivalent to a partially applied bracket") { implicit ec =>
    check { (acquire: IO[String], release: String => IO[Unit], f: String => IO[String]) =>
      acquire.bracket(f)(release) <-> Resource.make(acquire)(release).use(f)
    }
  }

  testAsync("Bracket on allocate is equivalent to use") { implicit ec =>
    check { r: Resource[IO, String] =>
      r.allocate.bracket(_._1.pure[IO])(_._2) <-> r.use(_.pure[IO])
    }
  }

  test("releases resources in reverse order of acquisition") {
    check { as: List[Either[Throwable, String]] =>
      var acquired: List[String] = Nil
      var released: List[String] = Nil

      val r = as.traverse { ea =>
        Resource.make(IO.fromEither(ea).flatMap(a => IO { acquired ::= a; a }))(
          a => IO { released ::= a })
      }

      r.attempt.use(IO.pure).unsafeRunSync()

      released.reverse <-> acquired
    }
  }

  test("releases both resources on combine") {
    check { (rx: Resource[IO, String], ry: Resource[IO, String]) =>
      var acquired: Set[String] = Set.empty
      var released: Set[String] = Set.empty

      def observe(r: Resource[IO, String]) = r.flatMap { a =>
        Resource.make(IO { acquired += a; a })(
          a => IO { released += a })
      }

      (observe(rx) combine observe(ry)).use(_ => IO.unit).attempt.unsafeRunSync()

      released <-> acquired
    }
  }

  test("releases both resources on combineK") {
    check { (rx: Resource[IO, String], ry: Resource[IO, String]) =>
      var acquired: Set[String] = Set.empty
      var released: Set[String] = Set.empty

      def observe(r: Resource[IO, String]) = r.flatMap { a =>
        Resource.make(IO { acquired += a; a })(a => IO { released += a })
      }

      (observe(rx) combineK observe(ry)).use(_ => IO.unit).attempt.unsafeRunSync()

      released <-> acquired
    }
  }

  testAsync("liftF") { implicit ec =>
    check { fa: IO[String] =>
      Resource.liftF(fa).use(IO.pure) <-> fa
    }
  }
}
