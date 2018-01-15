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

import java.util.concurrent.atomic.AtomicInteger

import cats.effect.internals.IOPlatform
import cats.effect.laws.discipline.EffectTests
import cats.effect.laws.discipline.arbitrary._
import cats.implicits._
import cats.kernel.laws.discipline.MonoidTests
import cats.laws._
import cats.laws.discipline._
import org.scalacheck._

import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

class IOTests extends BaseTestsSuite {

  checkAllAsync("IO", implicit ec => EffectTests[IO].effect[Int, Int, Int])
  checkAllAsync("IO", implicit ec => MonoidTests[IO[Int]].monoid)

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

  test("unsafeToFuture can yield immediate successful future") {
    val expected = IO(1).unsafeToFuture()
    expected.value shouldEqual Some(Success(1))
  }

  test("unsafeToFuture can yield immediate failed future") {
    val dummy = new RuntimeException("dummy")
    val expected = IO.raiseError(dummy).unsafeToFuture()
    expected.value shouldEqual Some(Failure(dummy))
  }

  test("fromEither handles Throwable in Left Projection") {
    case object Foo extends Exception
    val e : Either[Throwable, Nothing] = Left(Foo)

    IO.fromEither(e).attempt.unsafeRunSync() should matchPattern {
      case Left(Foo) => ()
    }
  }

  test("fromEither handles a Value in Right Projection") {
    case class Foo(x: Int)
    val e : Either[Throwable, Foo] = Right(Foo(1))

    IO.fromEither(e).attempt.unsafeRunSync() should matchPattern {
      case Right(Foo(_)) => ()
    }
  }

  testAsync("shift works for success") { implicit ec =>
    val expected = IO.shift.flatMap(_ => IO(1)).unsafeToFuture()
    expected.value shouldEqual None

    ec.tick()
    expected.value shouldEqual Some(Success(1))
  }

  testAsync("shift works for failure") { implicit ec =>
    val dummy = new RuntimeException("dummy")

    val expected = IO.shift.flatMap(_ => IO.raiseError(dummy)).unsafeToFuture()
    expected.value shouldEqual None

    ec.tick()
    expected.value shouldEqual Some(Failure(dummy))
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
      IO.fromFuture(IO(Future(f(a)))) <-> IO(f(a))
    }
  }

  testAsync("fromFuture works for successful completed futures") { implicit ec =>
    check { (a: Int) =>
      IO.fromFuture(IO.pure(Future.successful(a))) <-> IO.pure(a)
    }
  }

  testAsync("fromFuture works for exceptions") { implicit ec =>
    check { (ex: Throwable) =>
      val io = IO.fromFuture[Int](IO(Future(throw ex)))
      io <-> IO.raiseError[Int](ex)
    }
  }

  testAsync("fromFuture works for failed completed futures") { implicit ec =>
    check { (ex: Throwable) =>
      IO.fromFuture[Int](IO.pure(Future.failed(ex))) <-> IO.raiseError[Int](ex)
    }
  }

  testAsync("fromFuture protects against user code") { implicit ec =>
    check { (ex: Throwable) =>
      val io = IO.fromFuture[Int](IO(throw ex))
      io <-> IO.raiseError[Int](ex)
    }
  }

  testAsync("fromFuture suspends side-effects") { implicit ec =>
    check { (a: Int, f: (Int, Int) => Int, g: (Int, Int) => Int) =>
      var effect = a
      val io1 = IO.fromFuture(IO(Future { effect = f(effect, a) }))
      val io2 = IO.fromFuture(IO(Future { effect = g(effect, a) }))

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
    implicit val arbIO = Arbitrary(genSyncIO[Int])

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

  testAsync("io.attempt.to[IO] <-> io.attempt") { implicit ec =>
    check { (io: IO[Int]) =>
      val fa = io.attempt
      fa.to[IO] <-> fa
    }
  }

  testAsync("io.handleError(f).to[IO] <-> io.handleError(f)") { implicit ec =>
    val F = implicitly[Sync[IO]]

    check { (io: IO[Int], f: Throwable => IO[Int]) =>
      val fa = F.handleErrorWith(io)(f)
      fa.to[IO] <-> fa
    }
  }

  test("unsafeRunTimed throws for raiseError") {
    class DummyException extends RuntimeException("dummy")
    val dummy = new DummyException
    val err = IO.raiseError(dummy)
    intercept[DummyException] { err.unsafeRunTimed(Duration.Inf) }
  }

  test("unsafeRunTimed on flatMap chain") {
    val io = (0 until 100).foldLeft(IO(0))((io, _) => io.flatMap(x => IO.pure(x + 1)))
    io.unsafeRunSync() shouldEqual 100
  }

  test("unsafeRunTimed loop protects against user error in flatMap") {
    val dummy = new RuntimeException("dummy")
    val io = IO(1).flatMap(_ => throw dummy).attempt
    io.unsafeRunSync() shouldEqual Left(dummy)
  }

  test("unsafeRunTimed loop protects against user error in handleError") {
    val F = implicitly[Sync[IO]]
    val dummy1 = new RuntimeException("dummy1")
    val dummy2 = new RuntimeException("dummy2")

    val io = F.handleErrorWith(IO.raiseError(dummy1))(_ => throw dummy2).attempt
    io.unsafeRunSync() shouldEqual Left(dummy2)
  }

  test("suspend with unsafeRunSync") {
    val io = IO.suspend(IO(1)).map(_ + 1)
    io.unsafeRunSync() shouldEqual 2
  }

  test("map is stack-safe for unsafeRunSync") {
    import IOPlatform.{fusionMaxStackDepth => max}
    val f = (x: Int) => x + 1
    val io = (0 until (max * 10000)).foldLeft(IO(0))((acc, _) => acc.map(f))

    io.unsafeRunSync() shouldEqual max * 10000
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
    def bracket[A, B](acquire: IO[A])(use: A => IO[B])
                     (release: (A, BracketResult[Throwable, B]) => IO[Unit]): IO[B] =
      ref.bracket(acquire)(use)(release)
  }
}
