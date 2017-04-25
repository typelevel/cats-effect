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

import cats.effect.laws.discipline.EffectTests
import cats.implicits._
import cats.kernel._
import cats.kernel.laws.GroupLaws
import org.scalacheck._
import scala.util.{Failure, Success}

class IOTests extends BaseTestsSuite {
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

  test("throw in register is fail") {
    Prop.forAll { t: Throwable =>
      Eq[IO[Unit]].eqv(IO.async[Unit](_ => throw t), IO.raiseError(t))
    }
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

    val ioa = IO { throw Foo } ensuring IO.raiseError(Bar)

    ioa.attempt.unsafeRunSync() should matchPattern {
      case Left(Bar) => ()
    }
  }

  test("unsafeToFuture can yield immediate successful future") {
    val expected = IO(1).unsafeToFuture()
    expected.value shouldEqual Some(Success(1))
  }

  test("unsafeToFuture can yield immediate failed future") {
    val dummy = new RuntimeException("dummy")
    val expected = IO.raiseError(dummy).unsafeToFuture()
    expected.value shouldEqual Some(Failure(dummy))
  }

  testAsync("shift works for success") { implicit ec =>
    val expected = IO(1).shift.unsafeToFuture()
    expected.value shouldEqual None

    ec.tick()
    expected.value shouldEqual Some(Success(1))
  }

  testAsync("shift works for failure") { implicit ec =>
    val dummy = new RuntimeException("dummy")

    val expected = IO.raiseError(dummy).shift.unsafeToFuture()
    expected.value shouldEqual None

    ec.tick()
    expected.value shouldEqual Some(Failure(dummy))
  }

  testAsync("shift is stack safe") { implicit ec =>
    val io = (0 until 10000).foldLeft(IO(1))((io, _) => io.shift)

    val expected = io.unsafeToFuture()
    expected.value shouldEqual None

    ec.tick()
    expected.value shouldEqual Some(Success(1))
  }

  testAsync("shift is stack safe within flatMap loops") { implicit ec =>
    def signal(x: Int): IO[Int] =
      IO(x).shift

    def loop(n: Int): IO[Unit] =
      signal(n).flatMap { v =>
        if (v <= 0) IO.unit else loop(v - 1)
      }

    val expected = loop(100000).unsafeToFuture()
    expected.value shouldEqual None

    ec.tick()
    expected.value shouldEqual Some(Success(()))
  }

  testAsync("default Effect#shift is stack safe") { implicit ec =>
    import IOTests.{ioEffectDefaults => F}
    val io = (0 until 10000).foldLeft(IO(1)) { (io, _) => F.shift(io) }

    val expected = io.unsafeToFuture()
    expected.value shouldEqual None

    ec.tick()
    expected.value shouldEqual Some(Success(1))
  }

  testAsync("default Effect#shift is stack safe within flatMap loops") { implicit ec =>
    import IOTests.{ioEffectDefaults => F}

    def signal(x: Int): IO[Int] =
      F.shift(IO(x))

    def loop(n: Int): IO[Unit] =
      signal(n).flatMap { v =>
        if (v <= 0) IO.unit else loop(v - 1)
      }

    val expected = loop(100000).unsafeToFuture()
    expected.value shouldEqual None

    ec.tick()
    expected.value shouldEqual Some(Success(()))
  }

  implicit def eqIO[A: Eq]: Eq[IO[A]] = Eq by { ioa =>
    var result: Option[Either[Throwable, A]] = None

    ioa.runAsync(e => IO { result = Some(e) }).unsafeRunSync()

    result
  }

  implicit def eqThrowable: Eq[Throwable] =
    Eq.fromUniversalEquals[Throwable]
}

object IOTests {
  /** Implementation for testing default methods. */
  val ioEffectDefaults = new Effect[IO] {
    private val ref = implicitly[Effect[IO]]
    def async[A](k: ((Either[Throwable, A]) => Unit) => Unit): IO[A] =
      ref.async(k)
    def raiseError[A](e: Throwable): IO[A] =
      ref.raiseError(e)
    def handleErrorWith[A](fa: IO[A])(f: (Throwable) => IO[A]): IO[A] =
      ref.handleErrorWith(fa)(f)
    def pure[A](x: A): IO[A] =
      ref.pure(x)
    def flatMap[A, B](fa: IO[A])(f: (A) => IO[B]): IO[B] =
      ref.flatMap(fa)(f)
    def tailRecM[A, B](a: A)(f: (A) => IO[Either[A, B]]): IO[B] =
      ref.tailRecM(a)(f)
    def runAsync[A](fa: IO[A])(cb: (Either[Throwable, A]) => IO[Unit]): IO[Unit] =
      ref.runAsync(fa)(cb)
    def suspend[A](thunk: =>IO[A]): IO[A] =
      ref.suspend(thunk)
    def liftIO[A](ioa: IO[A]): IO[A] =
      ref.liftIO(ioa)
  }
}
