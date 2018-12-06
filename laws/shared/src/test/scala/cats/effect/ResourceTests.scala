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

  test("releases resources in reverse order of acquisition") {
    check { as: List[(Int, Either[Throwable, Unit])] =>
      var released: List[Int] = Nil
      val r = as.traverse { case (a, e) =>
        Resource.make(IO(a))(a => IO { released = a :: released } *> IO.fromEither(e))
      }
      r.use(IO.pure).attempt.unsafeRunSync()
      released <-> as.map(_._1)
    }
  }

  test("releases both resources on combine") {
    check { (rx: Resource[IO, Int], ry: Resource[IO, Int]) =>
      var acquired: Set[Int] = Set.empty
      var released: Set[Int] = Set.empty
      def observe(r: Resource[IO, Int]) = r.flatMap { a =>
        Resource.make(IO { acquired += a } *> IO.pure(a))(a => IO { released += a }).as(())
      }
      (observe(rx) combine observe(ry)).use(_ => IO.unit).attempt.unsafeRunSync()
      released <-> acquired
    }
  }
  test("releases both resources on combineK") {
    check { (rx: Resource[IO, Int], ry: Resource[IO, Int]) =>
      var acquired: Set[Int] = Set.empty
      var released: Set[Int] = Set.empty
      def observe(r: Resource[IO, Int]) = r.flatMap { a =>
        Resource.make(IO { acquired += a } *> IO.pure(a))(a => IO { released += a }).as(())
      }
      (observe(rx) combineK observe(ry)).use(_ => IO.unit).attempt.unsafeRunSync()
      released <-> acquired
    }
  }

  test("resource from AutoCloseable is auto closed") {
    val autoCloseable = new AutoCloseable {
      var closed = false
      override def close(): Unit = closed = true
    }

    val result = Resource.fromAutoCloseable(IO(autoCloseable))
      .use(source => IO.pure("Hello world")).unsafeRunSync()

    result shouldBe "Hello world"
    autoCloseable.closed shouldBe true
  }

  testAsync("liftF") { implicit ec =>
    check { fa: IO[String] =>
      Resource.liftF(fa).use(IO.pure) <-> fa
    }
  }

  testAsync("allocated produces the same value as the resource") { implicit ec =>
    check { resource: Resource[IO, Int] =>
      val a0 = Resource(resource.allocated).use(IO.pure).attempt
      val a1 = resource.use(IO.pure).attempt

      a0 <-> a1
    }
  }

  testAsync("map on lifted Resource") { implicit ec =>
    check { fa: IO[Int] =>
      Resource.liftF(fa).map(_ + 1).use(IO.pure) <-> fa.map(_ + 1)
    }
  }

  testAsync("map on pure Resource") { implicit ec =>
    check { a: Int =>
      Resource.pure[IO, Int](a).map(_ + 1).use(IO.pure) <-> IO.pure(a + 1)
    }
  }

  testAsync("map on lifted Resource is equivalent to flatMap with pure") { implicit ec =>
    check { (resource: Resource[IO, Int], fb: Int => IO[String]) =>
      resource.map(_ + 5).use(fb) <-> resource.flatMap(a => Resource.pure(a + 5)).use(fb)
    }
  }

  test("allocate does not release until close is invoked") {
    val released = new java.util.concurrent.atomic.AtomicBoolean(false)
    val release = Resource.make(IO.unit)(_ => IO(released.set(true)))
    val resource = Resource.liftF(IO.unit)

    val prog = for {
      res <- (release *> resource).allocated
      (_, close) = res
      releaseAfterF <- IO(released.get() shouldBe false)
      _ <- close >> IO(released.get() shouldBe true)
    } yield ()

    prog.unsafeRunSync
  }
  
  test("safe attempt suspended resource") {
    val exception = new Exception("boom!")
    val suspend = Resource.suspend[IO, Int](IO.raiseError(exception))
    suspend.attempt.use(IO.pure).unsafeRunSync() shouldBe Left(exception)
  }
}
