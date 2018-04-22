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
import cats.implicits._
import org.scalacheck._

class ResourceTests extends BaseTestsSuite {
  checkAllAsync("Resource[IO, ?]", implicit ec => MonadErrorTests[Resource[IO, ?], Throwable].monadError[Int, Int, Int])
  checkAllAsync("Resource[IO, Int]", implicit ec => MonoidTests[Resource[IO, Int]].monoid)

  testAsync("Resource.make is equivalent to a partially applied bracket") { implicit ec =>
    Prop.forAll { (acquire: IO[String], release: String => IO[Unit], f: String => IO[String]) =>
      acquire.bracket(f)(release) <-> Resource.make(acquire)(release).use(f)
    }
  }

  test("releases resources in reverse order of acquisition") {
    Prop.forAll { as: List[(String, Either[Throwable, Unit])] =>
      var released: List[String] = Nil
      val r = as.traverse { case (a, e) =>
        Resource.make(IO(a))(a => IO { released = a :: released } *> IO.fromEither(e))
      }
      r.use(IO.pure).unsafeRunSync()
      released <-> as.map(_._1)
    }
  }

  test("releases both resources on combine") {
    Prop.forAll { (rx: Resource[IO, String], ry: Resource[IO, String]) =>
      var released: Set[String] = Set.empty
      def observeRelease(r: Resource[IO, String]) = r.flatMap { a =>
        Resource.make(a.pure[IO])(a => IO { released += a }).map(Set(_))
      }
      val acquired = (observeRelease(rx) combine observeRelease(ry)).use(IO.pure).unsafeRunSync()
      released <-> acquired
    }
  }

  testAsync("liftF") { implicit ec =>
    Prop.forAll { fa: IO[String] =>
      Resource.liftF(fa).use(IO.pure) <-> fa
    }
  }
}
