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
import cats.implicits._
import cats.kernel.laws.discipline.{MonoidTests, SemigroupTests}
import cats.laws.discipline.ApplicativeTests
import org.scalacheck.{Arbitrary, Cogen}

class FiberTests extends BaseTestsSuite {
  implicit def genFiber[A: Arbitrary : Cogen]: Arbitrary[Fiber[IO, A]] =
    Arbitrary(genIO[A].map(io => Fiber(io, IO.unit)))

  implicit def fiberEq[F[_] : Applicative, A](implicit FA: Eq[F[A]]): Eq[Fiber[F, A]] =
    Eq.by[Fiber[F, A], F[A]](_.join)

  checkAllAsync("Fiber[IO, ?]",
    implicit ec => ApplicativeTests[Fiber[IO, ?]].applicative[Int, Int, Int])

  checkAllAsync("Fiber[IO, ?]",
    implicit ec => SemigroupTests[Fiber[IO, Int]](Fiber.fiberSemigroup[IO, Int]).semigroup)

  checkAllAsync("Fiber[IO, ?]",
    implicit ec => MonoidTests[Fiber[IO, Int]].monoid)

  testAsync("Applicative[Fiber[IO, ?].map2 preserves both cancelation tokens") { implicit ec =>
    var canceled = 0

    val io1 = IO.cancelable[Int](_ => IO { canceled += 1 })
    val io2 = IO.cancelable[Int](_ => IO { canceled += 1 })

    val f: IO[Unit] =
      for {
        fiber1 <- io1.start
        fiber2 <- io2.start
        _ <- fiber1.map2(fiber2)(_ + _).cancel
      } yield fiber2.join

    f.unsafeToFuture()
    ec.tick()
    canceled shouldBe 2
  }

  testAsync("Applicative[Fiber[IO, ?].map2 cancels first, when second terminates in error") { implicit ec =>
    val dummy = new RuntimeException("dummy")
    var wasCanceled = false

    val io1 = IO.cancelable[Int](_ => IO { wasCanceled = true })
    val io2 = IO.shift *> IO.raiseError[Int](dummy)

    val f: IO[Int] =
      for {
        fiber1 <- io1.start
        fiber2 <- io2.start
        io <- fiber1.map2(fiber2)(_ + _).join
      } yield io

    f.unsafeToFuture()
    ec.tick()
    wasCanceled shouldBe true
  }

  testAsync("Applicative[Fiber[IO, ?].map2 cancels second, when first terminates in error") { implicit ec =>
    val dummy = new RuntimeException("dummy")
    var wasCanceled = false

    val io1 = IO.shift *> IO.raiseError[Int](dummy)
    val io2 = IO.cancelable[Int](_ => IO { wasCanceled = true })

    val f: IO[Int] =
      for {
        fiber1 <- io1.start
        fiber2 <- io2.start
        io <- fiber1.map2(fiber2)(_ + _).join
      } yield io

    f.unsafeToFuture()
    ec.tick()
    wasCanceled shouldBe true
  }

  testAsync("Monoid[Fiber[IO, ?].combine cancels first, when second terminates in error") { implicit ec =>
    val dummy = new RuntimeException("dummy")
    var wasCanceled = false

    val io1 = IO.cancelable[Int](_ => IO { wasCanceled = true })
    val io2 = IO.shift *> IO.raiseError[Int](dummy)

    val f: IO[Int] =
      for {
        fiber1 <- io1.start
        fiber2 <- io2.start
        io <- fiber1.combine(fiber2).join
      } yield io

    f.unsafeToFuture()
    ec.tick()
    wasCanceled shouldBe true
  }

  testAsync("Monoid[Fiber[IO, ?].combine cancels second, when first terminates in error") { implicit ec =>
    val dummy = new RuntimeException("dummy")
    var wasCanceled = false

    val io1 = IO.shift *> IO.raiseError[Int](dummy)
    val io2 = IO.cancelable[Int](_ => IO { wasCanceled = true })

    val f: IO[Int] =
      for {
        fiber1 <- io1.start
        fiber2 <- io2.start
        io <- fiber1.combine(fiber2).join
      } yield io

    f.unsafeToFuture()
    ec.tick()
    wasCanceled shouldBe true
  }

  testAsync("Semigroup[Fiber[IO, ?].combine cancels first, when second terminates in error") { implicit ec =>
    val dummy = new RuntimeException("dummy")
    var wasCanceled = false

    val io1 = IO.cancelable[Int](_ => IO { wasCanceled = true })
    val io2 = IO.shift *> IO.raiseError[Int](dummy)

    val f: IO[Int] =
      for {
        fiber1 <- io1.start
        fiber2 <- io2.start
        io <- Fiber.fiberSemigroup[IO, Int].combine(fiber1, fiber2).join
      } yield io

    f.unsafeToFuture()
    ec.tick()
    wasCanceled shouldBe true
  }

  testAsync("Semigroup[Fiber[IO, ?].combine cancels second, when first terminates in error") { implicit ec =>
    val dummy = new RuntimeException("dummy")
    var wasCanceled = false

    val io1 = IO.shift *> IO.raiseError[Int](dummy)
    val io2 = IO.cancelable[Int](_ => IO { wasCanceled = true })

    val f: IO[Int] =
      for {
        fiber1 <- io1.start
        fiber2 <- io2.start
        io <- Fiber.fiberSemigroup[IO, Int].combine(fiber1, fiber2).join
      } yield io

    f.unsafeToFuture()
    ec.tick()
    wasCanceled shouldBe true
  }
}
