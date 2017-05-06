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

import cats.Eval.always
import java.util.concurrent.atomic.AtomicInteger
import cats.effect.internals.NonFatal
import cats.effect.laws.discipline.EffectTests
import cats.implicits._
import cats.kernel.laws.GroupLaws
import cats.laws._
import cats.laws.discipline._
import org.scalacheck._
import scala.concurrent.Future
import scala.util.{Failure, Success}

class IOTests extends BaseTestsSuite {
  import Generators._

  checkAllAsync("IO", implicit ec => EffectTests[IO].effect[Int, Int, Int])
  checkAllAsync("IO", implicit ec => GroupLaws[IO[Int]].monoid)

  test("defer evaluation until run") {
    var run = false
    val ioa = IO { run = true }
    run shouldEqual false
    ioa.unsafeRunSync()
    run shouldEqual true
  }

  testAsync("throw in register is fail") { implicit ec =>
    Prop.forAll { e: Throwable =>
      IO.async[Unit](_ => throw e) <-> IO.raiseError(e)
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

  testAsync("IO.async protects against multiple callback calls") { implicit ec =>
    val effect = new AtomicInteger()

    val io = IO.async[Int] { cb =>
      // Calling callback twice
      cb(Right(10))
      cb(Right(20))
    }

    io.unsafeRunAsync {
      case Right(v) => effect.addAndGet(v)
      case Left(ex) => throw ex
    }

    ec.tick()
    effect.get shouldEqual 10
  }

  testAsync("IO.async protects against thrown exceptions") { implicit ec =>
    val dummy = new RuntimeException("dummy")
    val io = IO.async[Int] { _ => throw dummy }
    val f = io.unsafeToFuture()

    ec.tick()
    f.value shouldEqual Some(Failure(dummy))
  }

  testAsync("IO.async does not break referential transparency") { implicit ec =>
    val io = IO.async[Int](_(Right(10)))
    val sum = for (a <- io; b <- io; c <- io) yield a + b + c
    val f = sum.unsafeToFuture()

    ec.tick()
    f.value shouldEqual Some(Success(30))
  }

  testAsync("fromFuture works for values") { implicit ec =>
    check { (a: Int, f: Int => Long) =>
      IO.fromFuture(always(Future(f(a)))) <-> IO(f(a))
    }
  }

  testAsync("fromFuture works for exceptions") { implicit ec =>
    check { (ex: Throwable) =>
      val io = IO.fromFuture[Int](always(Future(throw ex)))
      io <-> IO.raiseError[Int](ex)
    }
  }

  testAsync("fromFuture(always) protects against user code") { implicit ec =>
    check { (ex: Throwable) =>
      val io = IO.fromFuture[Int](always(throw ex))
      io <-> IO.raiseError[Int](ex)
    }
  }

  testAsync("fromFuture(always) suspends side-effects") { implicit ec =>
    check { (a: Int, f: (Int, Int) => Int, g: (Int, Int) => Int) =>
      var effect = a
      val io1 = IO.fromFuture(always(Future { effect = f(effect, a) }))
      val io2 = IO.fromFuture(always(Future { effect = g(effect, a) }))

      io2.flatMap(_ => io1).flatMap(_ => io2) <-> IO(g(f(g(a, a), a), a))
    }
  }

  testAsync("IO.pure(a).unsafeRunSyncOrFuture") { implicit ec =>
    check { (a: Int) =>
      IO.pure(a).unsafeRunSyncOrFuture() <-> Right(a)
    }
  }

  testAsync("IO.raiseError(e).unsafeRunSyncOrFuture") { implicit ec =>
    check { (e: Throwable) =>
      val received =
        try { IO.raiseError(e).unsafeRunSyncOrFuture(); None }
        catch { case NonFatal(e) => Some(e) }

      received <-> Some(e)
    }
  }

  testAsync("IO(a).unsafeRunSyncOrFuture") { implicit ec =>
    check { (a: Int, f: (Int => Long)) =>
      IO(f(a)).unsafeRunSyncOrFuture() <-> Right(f(a))
    }
  }

  testAsync("IO(throw e).unsafeRunSyncOrFuture") { implicit ec =>
    check { (e: Throwable) =>
      val received =
        try { IO(throw e).unsafeRunSyncOrFuture(); None }
        catch { case NonFatal(e) => Some(e) }

      received <-> Some(e)
    }
  }

  testAsync("IO(a).flatMap(f).unsafeRunSyncOrFuture") { implicit ec =>
    check { (a: Int, f: (Int => Long)) =>
      IO(a).flatMap(x => IO.pure(f(x))).unsafeRunSyncOrFuture() <-> Right(f(a))
    }
  }

  testAsync("IO.async(_(Right(a))).unsafeRunSyncOrFuture") { implicit ec =>
    check { (a: Int, f: (Int => Long)) =>
      val io = IO.async[Long](cb => cb(Right(f(a))))
      io.unsafeRunSyncOrFuture() <-> Left(Future.successful(f(a)))
    }
  }

  testAsync("IO.async(_(Left(e))).unsafeRunSyncOrFuture") { implicit ec =>
    check { (e: Throwable) =>
      val io = IO.async[Long](cb => cb(Left(e)))
      io.unsafeRunSyncOrFuture() <-> Left(Future.failed(e))
    }
  }
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
