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

import java.util.concurrent.atomic.AtomicInteger
import cats.effect.internals.{Callback, IOPlatform}
import cats.effect.laws.discipline.ConcurrentEffectTests
import cats.effect.laws.discipline.arbitrary._
import cats.implicits._
import cats.kernel.laws.discipline.MonoidTests
import cats.laws._
import cats.laws.discipline._
import org.scalacheck._
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success}


class IOTests extends BaseTestsSuite {
  checkAllAsync("IO", implicit ec => ConcurrentEffectTests[IO].concurrentEffect[Int, Int, Int])
  checkAllAsync("IO", implicit ec => MonoidTests[IO[Int]].monoid)
  checkAllAsync("IO", implicit ec => SemigroupKTests[IO].semigroupK[Int])

  checkAllAsync("IO.Par", implicit ec => ApplicativeTests[IO.Par].applicative[Int, Int, Int])
  checkAllAsync("IO", implicit ec => ParallelTests[IO, IO.Par].parallel[Int, Int])

  checkAllAsync("IO(defaults)", implicit ec => {
    implicit val ioEffect = IOTests.ioEffectDefaults
    ConcurrentEffectTests[IO].concurrentEffect[Int, Int, Int]
  })

  test("IO.Par's applicative instance is different") {
    implicitly[Applicative[IO]] shouldNot be(implicitly[Applicative[IO.Par]])
  }

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

  testAsync("thrown exceptions after callback was called once are re-thrown") { implicit ec =>
    val dummy = new RuntimeException("dummy")
    val io = IO.async[Int] { cb =>
      cb(Right(10))
      throw dummy
    }

    var effect: Option[Either[Throwable, Int]] = None
    val sysErr = catchSystemErr {
      io.unsafeRunAsync { v => effect = Some(v) }
      ec.tick()
    }

    effect shouldEqual Some(Right(10))
    sysErr should include("dummy")
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

  testAsync("shift works for success (via Timer)") { implicit ec =>
    val expected = IO.shift.flatMap(_ => IO(1)).unsafeToFuture()
    expected.value shouldEqual None

    ec.tick()
    expected.value shouldEqual Some(Success(1))
  }

  testAsync("shift works for success (via ExecutionContext)") { ec =>
    val expected = IO.shift(ec).flatMap(_ => IO(1)).unsafeToFuture()
    expected.value shouldEqual None

    ec.tick()
    expected.value shouldEqual Some(Success(1))
  }


  testAsync("shift works for failure (via Timer)") { implicit ec =>
    val dummy = new RuntimeException("dummy")

    val expected = IO.shift.flatMap(_ => IO.raiseError(dummy)).unsafeToFuture()
    expected.value shouldEqual None

    ec.tick()
    expected.value shouldEqual Some(Failure(dummy))
  }

  testAsync("Async.shift[IO]") { implicit ec =>
    val f = Async.shift[IO](ec).unsafeToFuture()
    f.value shouldEqual None
    ec.tick()
    f.value shouldEqual Some(Success(()))
  }

  testAsync("shift works for failure (via ExecutionContext)") { ec =>
    val dummy = new RuntimeException("dummy")

    val expected = IO.shift(ec).flatMap(_ => IO.raiseError(dummy)).unsafeToFuture()
    expected.value shouldEqual None

    ec.tick()
    expected.value shouldEqual Some(Failure(dummy))
  }

  testAsync("IO.sleep") { ec =>
    implicit val timer = ec.timer[IO]

    val io = IO.sleep(10.seconds) *> IO(1 + 1)
    val f = io.unsafeToFuture()

    ec.tick()
    f.value shouldEqual None
    ec.tick(9.seconds)
    f.value shouldEqual None
    ec.tick(1.second)
    f.value shouldEqual Some(Success(2))
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

  testAsync("parMap2 for successful values") { implicit ec =>
    val io1 = IO.shift *> IO.pure(1)
    val io2 = IO.shift *> IO.pure(2)

    val io3 = (io1, io2).parMapN(_ + _)
    val f = io3.unsafeToFuture()
    ec.tick()
    f.value shouldEqual Some(Success(3))
  }

  testAsync("parMap2 can fail for one") { implicit ec =>
    val dummy = new RuntimeException("dummy")
    val io1 = IO.shift *> IO.pure(1)
    val io2 = IO.shift *> IO.raiseError[Int](dummy)

    val io3 = (io1, io2).parMapN(_ + _)
    val f1 = io3.unsafeToFuture()

    ec.tick()
    f1.value shouldEqual Some(Failure(dummy))

    val io4 = (io2, io1).parMapN(_ + _)
    val f2 = io4.unsafeToFuture()

    ec.tick()
    f2.value shouldEqual Some(Failure(dummy))
  }

  testAsync("parMap2 can fail for both, with left failing first") { implicit ec =>
    val error = catchSystemErr {
      val dummy1 = new RuntimeException("dummy1")
      val dummy2 = new RuntimeException("dummy2")

      val io1 = IO.raiseError[Int](dummy1)
      val io2 = IO.shift *> IO.raiseError[Int](dummy2)
      val io3 = (io1, io2).parMapN(_ + _)

      val f1 = io3.unsafeToFuture()
      ec.tick()
      f1.value shouldBe Some(Failure(dummy1))
    }

    error should include("dummy2")
  }

  testAsync("parMap2 can fail for both, with right failing first") { implicit ec =>
    val error = catchSystemErr {
      val dummy1 = new RuntimeException("dummy1")
      val dummy2 = new RuntimeException("dummy2")

      val io1 = IO.shift *> IO.raiseError[Int](dummy1)
      val io2 = IO.raiseError[Int](dummy2)
      val io3 = (io1, io2).parMapN(_ + _)

      val f1 = io3.unsafeToFuture()
      ec.tick()
      f1.value shouldBe Some(Failure(dummy2))
    }

    error should include("dummy1")
  }

  testAsync("parMap2 is stack safe") { implicit ec =>
    val count = if (IOPlatform.isJVM) 100000 else 5000
    val io = (0 until count).foldLeft(IO(0))((acc, e) => (acc, IO(e)).parMapN(_ + _))

    val f = io.unsafeToFuture()
    f.value shouldEqual Some(Success(count * (count - 1) / 2))
  }

  testAsync("parMap2 cancels first, when second terminates in error") { implicit ec =>
    val dummy = new RuntimeException("dummy")
    var wasCanceled = false

    val io1 = IO.cancelable[Int](_ => IO { wasCanceled = true })
    val io2 = IO.shift *> IO.raiseError[Int](dummy)

    val f = (io1, io2).parMapN((_, _) => ()).unsafeToFuture()
    ec.tick()

    wasCanceled shouldBe true
    f.value shouldBe Some(Failure(dummy))
  }

  testAsync("parMap2 cancels second, when first terminates in error") { implicit ec =>
    val dummy = new RuntimeException("dummy")
    var wasCanceled = false

    val io1 = IO.shift *> IO.raiseError[Int](dummy)
    val io2 = IO.cancelable[Int](_ => IO { wasCanceled = true })

    val f = (io1, io2).parMapN((_, _) => ()).unsafeToFuture()
    ec.tick()

    wasCanceled shouldBe true
    f.value shouldBe Some(Failure(dummy))
  }

  testAsync("IO.cancelable IOs can be canceled") { implicit ec =>
    var wasCanceled = false
    val p = Promise[Int]()

    val io1 = IO.shift *> IO.cancelable[Int](_ => IO { wasCanceled = true })
    val cancel = io1.unsafeRunCancelable(Callback.promise(p))

    cancel()
    // Signal not observed yet due to IO.shift
    wasCanceled shouldBe false

    ec.tick()
    wasCanceled shouldBe true
    p.future.value shouldBe None
  }

  testAsync("IO#redeem(throw, f) <-> IO#map") { implicit ec =>
    check { (io: IO[Int], f: Int => Int) =>
      io.redeem(e => throw e, f) <-> io.map(f)
    }
  }

  testAsync("IO#redeem(f, identity) <-> IO#handleError") { implicit ec =>
    check { (io: IO[Int], f: Throwable => Int) =>
      io.redeem(f, identity) <-> io.handleError(f)
    }
  }

  testAsync("IO#redeemWith(raiseError, f) <-> IO#flatMap") { implicit ec =>
    check { (io: IO[Int], f: Int => IO[Int]) =>
      io.redeemWith(IO.raiseError, f) <-> io.flatMap(f)
    }
  }

  testAsync("IO#redeemWith(f, pure) <-> IO#handleErrorWith") { implicit ec =>
    check { (io: IO[Int], f: Throwable => IO[Int]) =>
      io.redeemWith(f, IO.pure) <-> io.handleErrorWith(f)
    }
  }

  test("runSyncStep pure produces right IO") {
    IO.pure(42).runSyncStep.unsafeRunSync() shouldBe Right(42)
  }

  test("runSyncStep raiseError produces error IO") {
    val e = new Exception
    IO.raiseError(e).runSyncStep.attempt.unsafeRunSync() shouldBe Left(e)
  }

  test("runSyncStep delay produces right IO") {
    var v = 42
    val io = (IO { v += 1 }).runSyncStep
    v shouldBe 42
    io.unsafeRunSync() shouldBe Right(())
    v shouldBe 43
    io.unsafeRunSync() shouldBe Right(())
    v shouldBe 44
  }

  test("runSyncStep runs bind chain") {
    var v = 42
    val tsk = IO.pure(42).flatMap { x =>
      (IO { v += x }).flatMap { _ =>
        IO.pure(x)
      }
    }
    val io = tsk.runSyncStep
    v shouldBe 42
    io.unsafeRunSync() shouldBe Right(42)
    v shouldBe 84
    io.unsafeRunSync() shouldBe Right(42)
    v shouldBe 126
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
    def runSyncStep[A](fa: IO[A]): IO[Either[IO[A], A]] =
      ref.runSyncStep(fa)
    def suspend[A](thunk: =>IO[A]): IO[A] =
      ref.suspend(thunk)
    def runCancelable[A](fa: IO[A])(cb: Either[Throwable, A] => IO[Unit]): IO[IO[Unit]] =
      fa.runCancelable(cb)
    def cancelable[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): IO[A] =
      IO.cancelable(k)
    def start[A](fa: IO[A]): IO[Fiber[IO, A]] =
      fa.start
    def bracketCase[A, B](acquire: IO[A])
      (use: A => IO[B])
      (release: (A, ExitCase[Throwable]) => IO[Unit]): IO[B] =
      ref.bracketCase(acquire)(use)(release)
  }
}
