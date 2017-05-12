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

  testAsync("attempt flatMap loop") { implicit ec =>
    def loop[A](source: IO[A], n: Int): IO[A] =
      source.attempt.flatMap {
        case Right(a) =>
          if (n <= 0) IO.pure(a)
          else loop(source, n - 1)
        case Left(e) =>
          IO.raiseError(e)
      }

    val f = loop(IO("value"), 10000).unsafeToFuture()

    ec.tick()
    f.value shouldEqual Some(Success("value"))
  }

  testAsync("attempt foldLeft sequence") { implicit ec =>
    val count = 10000
    val loop = (0 until count).foldLeft(IO(0)) { (acc, _) =>
      acc.attempt.flatMap {
        case Right(x) => IO.pure(x + 1)
        case Left(e) => IO.raiseError(e)
      }
    }

    val f = loop.unsafeToFuture()

    ec.tick()
    f.value shouldEqual Some(Success(count))
  }

  testAsync("IO(throw ex).attempt.map") { implicit ec =>
    val dummy = new RuntimeException("dummy")
    val io = IO[Int](throw dummy).attempt.map {
      case Left(`dummy`) => 100
      case _ => 0
    }

    val f = io.unsafeToFuture(); ec.tick()
    f.value shouldEqual Some(Success(100))
  }

  testAsync("IO(throw ex).flatMap.attempt.map") { implicit ec =>
    val dummy = new RuntimeException("dummy")
    val io = IO[Int](throw dummy).flatMap(IO.pure).attempt.map {
      case Left(`dummy`) => 100
      case _ => 0
    }

    val f = io.unsafeToFuture(); ec.tick()
    f.value shouldEqual Some(Success(100))
  }

  testAsync("IO(throw ex).map.attempt.map") { implicit ec =>
    val dummy = new RuntimeException("dummy")
    val io = IO[Int](throw dummy).map(x => x).attempt.map {
      case Left(`dummy`) => 100
      case _ => 0
    }

    val f = io.unsafeToFuture(); ec.tick()
    f.value shouldEqual Some(Success(100))
  }

  testAsync("IO.async.attempt.map") { implicit ec =>
    val dummy = new RuntimeException("dummy")
    val source = IO.async[Int] { callback =>
      ec.execute(new Runnable {
        def run(): Unit =
          callback(Left(dummy))
      })
    }

    val io = source.attempt.map {
      case Left(`dummy`) => 100
      case _ => 0
    }

    val f = io.unsafeToFuture(); ec.tick()
    f.value shouldEqual Some(Success(100))
  }

  testAsync("IO.async.flatMap.attempt.map") { implicit ec =>
    val dummy = new RuntimeException("dummy")
    val source = IO.async[Int] { callback =>
      ec.execute(new Runnable {
        def run(): Unit =
          callback(Left(dummy))
      })
    }

    val io = source.flatMap(IO.pure).attempt.map {
      case Left(`dummy`) => 100
      case _ => 0
    }

    val f = io.unsafeToFuture(); ec.tick()
    f.value shouldEqual Some(Success(100))
  }

  testAsync("IO.async.attempt.flatMap") { implicit ec =>
    val dummy = new RuntimeException("dummy")
    val source = IO.async[Int] { callback =>
      ec.execute(new Runnable {
        def run(): Unit =
          callback(Left(dummy))
      })
    }

    val io = source.attempt.flatMap {
      case Left(`dummy`) => IO.pure(100)
      case _ => IO.pure(0)
    }

    val f = io.unsafeToFuture(); ec.tick()
    f.value shouldEqual Some(Success(100))
  }

  def repeatedTransformLoop[A](n: Int, io: IO[A]): IO[A] =
    io.to[IO].flatMap { x =>
      if (n <= 0) IO.pure(x).to[IO]
      else repeatedTransformLoop(n - 1, io)
    }

  testAsync("io.to[IO] <-> io") { implicit ec =>
    check { (io: IO[Int]) => io.to[IO] <-> io }
  }

  testAsync("sync.to[IO] is stack-safe") { implicit ec =>
    // Override default generator to only generate
    // synchronous instances that are stack-safe
    implicit val arbIO: Arbitrary[IO[Int]] =
      Arbitrary(Gen.delay(genSyncIO[Int]))

    check { (io: IO[Int]) =>
      repeatedTransformLoop(10000, io) <-> io
    }
  }

  testAsync("async.to[IO] is stack-safe if the source is") { implicit ec =>
    // Stack-safe async IO required
    def async(a: Int) = IO.async[Int] { cb =>
      ec.execute(new Runnable {
        def run(): Unit =
          cb(Right(a))
      })
    }

    val f = repeatedTransformLoop(10000, async(99)).unsafeToFuture()
    ec.tick()
    f.value shouldEqual Some(Success(99))
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
  }
}
