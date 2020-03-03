/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

import cats.effect.concurrent.Deferred
import cats.effect.internals.{Callback, IOPlatform}
import cats.effect.laws.discipline.{ConcurrentEffectTests, EffectTests}
import cats.effect.laws.discipline.arbitrary._
import cats.implicits._
import cats.kernel.laws.discipline.MonoidTests
import cats.laws._
import cats.laws.discipline._
import cats.laws.discipline.arbitrary._
import org.scalacheck._

import scala.concurrent.{ExecutionContext, Future, Promise, TimeoutException}
import scala.util.{Failure, Success, Try}
import scala.concurrent.duration._

class IOTests extends BaseTestsSuite {
  checkAllAsync("IO", implicit ec => {
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    ConcurrentEffectTests[IO].concurrentEffect[Int, Int, Int]
  })

  checkAllAsync("IO", implicit ec => MonoidTests[IO[Int]].monoid)
  checkAllAsync("IO", implicit ec => SemigroupKTests[IO].semigroupK[Int])
  checkAllAsync("IO", implicit ec => AlignTests[IO].align[Int, Int, Int, Int])

  checkAllAsync("IO.Par", implicit ec => {
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    CommutativeApplicativeTests[IO.Par].commutativeApplicative[Int, Int, Int]
  })

  checkAllAsync("IO.Par", implicit ec => {
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    AlignTests[IO.Par].align[Int, Int, Int, Int]
  })

  checkAllAsync(
    "IO",
    implicit ec => {
      implicit val cs: ContextShift[IO] = ec.ioContextShift

      // do NOT inline this val; it causes the 2.13.0 compiler to crash for... reasons (see: scala/bug#11732)
      val module = ParallelTests[IO]
      module.parallel[Int, Int]
    }
  )

  checkAllAsync("IO(Effect defaults)", implicit ec => {
    implicit val ioEffect: Effect[IO] = IOTests.ioEffectDefaults
    EffectTests[IO].effect[Int, Int, Int]
  })

  checkAllAsync(
    "IO(ConcurrentEffect defaults)",
    implicit ec => {
      implicit val cs: ContextShift[IO] = ec.ioContextShift
      implicit val ioConcurrent: ConcurrentEffect[IO] = IOTests.ioConcurrentEffectDefaults(ec)
      ConcurrentEffectTests[IO].concurrentEffect[Int, Int, Int]
    }
  )

  testAsync("IO.Par's applicative instance is different") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
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
    check { (e: Throwable) =>
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
      io.unsafeRunAsync { v =>
        effect = Some(v)
      }
      ec.tick()
    }

    effect shouldEqual Some(Right(10))
    sysErr should include("dummy")
  }

  test("catch exceptions within main block") {
    case object Foo extends Exception

    val ioa = IO(throw Foo)

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
    val e: Either[Throwable, Nothing] = Left(Foo)

    IO.fromEither(e).attempt.unsafeRunSync() should matchPattern {
      case Left(Foo) => ()
    }
  }

  test("fromEither handles a Value in Right Projection") {
    case class Foo(x: Int)
    val e: Either[Throwable, Foo] = Right(Foo(1))

    IO.fromEither(e).attempt.unsafeRunSync() should matchPattern {
      case Right(Foo(_)) => ()
    }
  }

  test("fromTry handles Failure") {
    case object Foo extends Exception
    val t: Try[Nothing] = Failure(Foo)

    IO.fromTry(t).attempt.unsafeRunSync() should matchPattern {
      case Left(Foo) => ()
    }
  }

  test("fromTry handles Success") {
    case class Foo(x: Int)
    val t: Try[Foo] = Success(Foo(1))

    IO.fromTry(t).attempt.unsafeRunSync() should matchPattern {
      case Right(Foo(_)) => ()
    }
  }

  testAsync("shift works for success (via Timer)") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

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
    implicit val cs: ContextShift[IO] = ec.ioContextShift
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
    implicit val timer: Timer[IO] = ec.timer[IO]

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
    val io = IO.async[Int] { _ =>
      throw dummy
    }
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
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)
    check { (a: Int, f: Int => Long) =>
      IO.fromFuture(IO(Future(f(a)))) <-> IO(f(a))
    }
  }

  testAsync("fromFuture works for successful completed futures") { implicit ec =>
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)
    check { (a: Int) =>
      IO.fromFuture(IO.pure(Future.successful(a))) <-> IO.pure(a)
    }
  }

  testAsync("fromFuture works for exceptions") { implicit ec =>
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)
    check { (ex: Throwable) =>
      val io = IO.fromFuture[Int](IO(Future(throw ex)))
      io <-> IO.raiseError[Int](ex)
    }
  }

  testAsync("fromFuture works for failed completed futures") { implicit ec =>
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)
    check { (ex: Throwable) =>
      IO.fromFuture[Int](IO.pure(Future.failed(ex))) <-> IO.raiseError[Int](ex)
    }
  }

  testAsync("fromFuture protects against user code") { implicit ec =>
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)
    check { (ex: Throwable) =>
      val io = IO.fromFuture[Int](IO(throw ex))
      io <-> IO.raiseError[Int](ex)
    }
  }

  testAsync("fromFuture suspends side-effects") { implicit ec =>
    implicit val cs: ContextShift[IO] = IO.contextShift(ec)
    check { (a: Int, f: (Int, Int) => Int, g: (Int, Int) => Int) =>
      var effect = a
      val io1 = IO.fromFuture(IO(Future { effect = f(effect, a) }))
      val io2 = IO.fromFuture(IO(Future { effect = g(effect, a) }))

      io2.flatMap(_ => io1).flatMap(_ => io2) <-> IO(g(f(g(a, a), a), a)).void
    }
  }

  testAsync("attempt flatMap loop") { implicit ec =>
    def loop[A](source: IO[A], n: Int): IO[A] =
      source.attempt.flatMap {
        case Right(l) =>
          if (n <= 0) IO.pure(l)
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
        case Left(e)  => IO.raiseError(e)
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
      case _             => 0
    }

    val f = io.unsafeToFuture(); ec.tick()
    f.value shouldEqual Some(Success(100))
  }

  testAsync("IO(throw ex).flatMap.attempt.map") { implicit ec =>
    val dummy = new RuntimeException("dummy")
    val io = IO[Int](throw dummy).flatMap(IO.pure).attempt.map {
      case Left(`dummy`) => 100
      case _             => 0
    }

    val f = io.unsafeToFuture(); ec.tick()
    f.value shouldEqual Some(Success(100))
  }

  testAsync("IO(throw ex).map.attempt.map") { implicit ec =>
    val dummy = new RuntimeException("dummy")
    val io = IO[Int](throw dummy).map(x => x).attempt.map {
      case Left(`dummy`) => 100
      case _             => 0
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
      case _             => 0
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
      case _             => 0
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
      case _             => IO.pure(0)
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
    check { (io: IO[Int]) =>
      io.to[IO] <-> io
    }
  }

  testAsync("sync.to[IO] is stack-safe") { implicit ec =>
    // Override default generator to only generate
    // synchronous instances that are stack-safe
    implicit val arbIO: Arbitrary[IO[Int]] = Arbitrary(genSyncIO[Int].map(_.toIO))

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
    intercept[DummyException](err.unsafeRunTimed(Duration.Inf))
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

  test("uncancelable with unsafeRunSync") {
    val io = IO.pure(1).uncancelable
    io.unsafeRunSync() shouldBe 1
  }

  test("map is stack-safe for unsafeRunSync") {
    import IOPlatform.{fusionMaxStackDepth => max}
    val f = (x: Int) => x + 1
    val io = (0 until (max * 10000)).foldLeft(IO(0))((acc, _) => acc.map(f))

    io.unsafeRunSync() shouldEqual max * 10000
  }

  testAsync("parMap2 for successful values") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val io1 = IO.shift *> IO.pure(1)
    val io2 = IO.shift *> IO.pure(2)

    val io3 = (io1, io2).parMapN(_ + _)
    val f = io3.unsafeToFuture()
    ec.tick()
    f.value shouldEqual Some(Success(3))
  }

  testAsync("parMap2 can fail for one") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

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

  testAsync("parMap2 can fail for both, with non-deterministic failure") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    catchSystemErr {
      val dummy1 = new RuntimeException("dummy1")
      val dummy2 = new RuntimeException("dummy2")

      val io1 = IO.raiseError[Int](dummy1)
      val io2 = IO.raiseError[Int](dummy2)
      val io3 = (io1, io2).parMapN(_ + _)

      val f1 = io3.unsafeToFuture()
      ec.tick()
      val exc = f1.value.get.failed.get
      exc should (be(dummy1).or(be(dummy2)))
    }
  }

  testAsync("parMap2 is stack safe") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val count = if (IOPlatform.isJVM) 100000 else 5000
    val io = (0 until count).foldLeft(IO(0))((acc, e) => (acc, IO(e)).parMapN(_ + _))

    val f = io.unsafeToFuture()
    ec.tick(1.day)
    f.value shouldEqual Some(Success(count * (count - 1) / 2))
  }

  testAsync("parMap2 cancels first, when second terminates in error") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val dummy = new RuntimeException("dummy")
    var wasCanceled = false

    val latch = Promise[Unit]()
    val io1 = IO.cancelable[Int] { _ =>
      latch.success(()); IO { wasCanceled = true }
    }
    val io2 = IO.shift *> IO.raiseError[Int](dummy)

    val f = (io1, io2).parMapN((_, _) => ()).unsafeToFuture()
    ec.tick()

    (wasCanceled || latch.future.value.isEmpty) shouldBe true
    f.value shouldBe Some(Failure(dummy))
  }

  testAsync("parMap2 cancels second, when first terminates in error") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    val dummy = new RuntimeException("dummy")
    var wasCanceled = false

    val latch = Promise[Unit]()
    val io1 = IO.shift *> IO.raiseError[Int](dummy)
    val io2 = IO.cancelable[Int] { _ =>
      latch.success(()); IO { wasCanceled = true }
    }

    val f = (io1, io2).parMapN((_, _) => ()).unsafeToFuture()
    ec.tick()

    (wasCanceled || !latch.future.isCompleted) shouldBe true
    f.value shouldBe Some(Failure(dummy))
  }

  testAsync("IO.cancelable IOs can be canceled") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift

    var wasCanceled = false
    val p = Promise[Int]()

    val latch = Promise[Unit]()
    val io1 = IO.shift *> IO.cancelable[Int] { _ =>
      latch.success(()); IO { wasCanceled = true }
    }
    val cancel = io1.unsafeRunCancelable(Callback.promise(p))

    cancel.unsafeRunSync()
    // Signal not observed yet due to IO.shift
    wasCanceled shouldBe false

    ec.tick()
    (wasCanceled || latch.future.value.isEmpty) shouldBe true
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

  testAsync("IO.timeout can mirror the source") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    implicit val timer: Timer[IO] = ec.timer[IO]

    check { (ioa: IO[Int]) =>
      ioa.timeout(1.day) <-> ioa
    }
  }

  testAsync("IO.timeout can end in timeout") { implicit ec =>
    implicit val cs: ContextShift[IO] = ec.ioContextShift
    implicit val timer: Timer[IO] = ec.timer[IO]

    val task = for {
      p <- Deferred[IO, Unit]
      f <- IO.unit.bracket(_ => IO.sleep(1.day) *> IO(1))(_ => p.complete(())).timeout(1.second).start
      _ <- p.get
      _ <- f.join
    } yield ()

    val f = task.unsafeToFuture()
    ec.tick()
    f.value shouldBe None

    ec.tick(1.second)
    f.value.get.failed.get shouldBe an[TimeoutException]
  }

  test("bracket signals the error in use") {
    val e = new RuntimeException("error in use")

    val r = IO.unit
      .bracket(_ => IO.raiseError(e))(_ => IO.unit)
      .attempt
      .unsafeRunSync()

    r shouldEqual Left(e)
    e.getSuppressed shouldBe empty // ensure memory isn't leaked with addSuppressed
  }

  test("bracket signals the error in release") {
    val e = new RuntimeException("error in release")

    val r = IO.unit
      .bracket(_ => IO.unit)(_ => IO.raiseError(e))
      .attempt
      .unsafeRunSync()

    r shouldEqual Left(e)
    e.getSuppressed shouldBe empty // ensure memory isn't leaked with addSuppressed
  }

  test("bracket signals the error in use and logs the error from release") {
    val e1 = new RuntimeException("error in use")
    val e2 = new RuntimeException("error in release")

    var r: Option[Either[Throwable, Nothing]] = None
    val sysErr = catchSystemErr {
      r = Some(
        IO.unit
          .bracket(_ => IO.raiseError(e1))(_ => IO.raiseError(e2))
          .attempt
          .unsafeRunSync()
      )
    }

    r shouldEqual Some(Left(e1))
    sysErr should include("error in release")
    e1.getSuppressed shouldBe empty // ensure memory isn't leaked with addSuppressed
    e2.getSuppressed shouldBe empty // ensure memory isn't leaked with addSuppressed
  }

  test("unsafeRunSync works for bracket") {
    var effect = 0
    val io = IO(1).bracket(x => IO(x + 1))(_ => IO(effect += 1))
    io.unsafeRunSync() shouldBe 2
    effect shouldBe 1
  }

  testAsync("bracket does not evaluate use on cancel") { implicit ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift
    implicit val timer: Timer[IO] = ec.timer[IO]

    var use = false
    var release = false

    val task = IO
      .sleep(2.second)
      .bracket(_ => IO { use = true })(_ => IO { release = true })
      .timeoutTo[Unit](1.second, IO.never)

    val f = task.unsafeToFuture()
    ec.tick(2.second)

    f.value shouldBe None
    use shouldBe false
    release shouldBe true
  }

  test("unsafeRunSync works for IO.cancelBoundary") {
    val io = IO.cancelBoundary *> IO(1)
    io.unsafeRunSync() shouldBe 1
  }

  testAsync("racePair should be stack safe, take 1") { implicit ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift

    val count = if (IOPlatform.isJVM) 100000 else 1000
    val tasks = (0 until count).map(_ => IO.shift *> IO(1))
    val init = IO.never: IO[Int]

    val sum = tasks.foldLeft(init)((acc, t) =>
      IO.racePair(acc, t).map {
        case Left((l, _))  => l
        case Right((_, r)) => r
      }
    )

    val f = sum.unsafeToFuture()
    ec.tick()
    f.value shouldBe Some(Success(1))
  }

  testAsync("racePair should be stack safe, take 2") { implicit ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift

    val count = if (IOPlatform.isJVM) 100000 else 1000
    val tasks = (0 until count).map(_ => IO(1))
    val init = IO.never: IO[Int]

    val sum = tasks.foldLeft(init)((acc, t) =>
      IO.racePair(acc, t).map {
        case Left((l, _))  => l
        case Right((_, r)) => r
      }
    )

    val f = sum.unsafeToFuture()
    ec.tick()
    f.value shouldBe Some(Success(1))
  }

  testAsync("racePair has a stack safe cancelable") { implicit ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift

    val count = if (IOPlatform.isJVM) 10000 else 1000
    val p = Promise[Int]()

    val tasks = (0 until count).map(_ => IO.never: IO[Int])
    val all = tasks.foldLeft(IO.never: IO[Int])((acc, t) =>
      IO.racePair(acc, t).flatMap {
        case Left((l, fr))  => fr.cancel.map(_ => l)
        case Right((fl, r)) => fl.cancel.map(_ => r)
      }
    )

    val f = IO
      .racePair(IO.fromFuture(IO.pure(p.future)), all)
      .flatMap {
        case Left((l, fr))  => fr.cancel.map(_ => l)
        case Right((fl, r)) => fl.cancel.map(_ => r)
      }
      .unsafeToFuture()

    ec.tick()
    p.success(1)
    ec.tick()

    f.value shouldBe Some(Success(1))
  }

  testAsync("racePair avoids extraneous async boundaries") { implicit ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift

    val f = IO
      .racePair(IO.shift *> IO(1), IO.shift *> IO(1))
      .flatMap {
        case Left((l, fiber))  => fiber.join.map(_ + l)
        case Right((fiber, r)) => fiber.join.map(_ + r)
      }
      .unsafeToFuture()

    f.value shouldBe None
    ec.tickOne()
    f.value shouldBe None
    ec.tickOne()
    f.value shouldBe Some(Success(2))
  }

  testAsync("race should be stack safe, take 1") { implicit ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift

    val count = if (IOPlatform.isJVM) 100000 else 1000
    val tasks = (0 until count).map(_ => IO.shift *> IO(1))
    val init = IO.never: IO[Int]

    val sum = tasks.foldLeft(init)((acc, t) =>
      IO.race(acc, t).map {
        case Left(l)  => l
        case Right(r) => r
      }
    )

    val f = sum.unsafeToFuture()
    ec.tick()
    f.value shouldBe Some(Success(1))
  }

  testAsync("race should be stack safe, take 2") { implicit ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift

    val count = if (IOPlatform.isJVM) 100000 else 1000
    val tasks = (0 until count).map(_ => IO(1))
    val init = IO.never: IO[Int]

    val sum = tasks.foldLeft(init)((acc, t) =>
      IO.race(acc, t).map {
        case Left(l)  => l
        case Right(r) => r
      }
    )

    val f = sum.unsafeToFuture()
    ec.tick()
    f.value shouldBe Some(Success(1))
  }

  testAsync("race has a stack safe cancelable") { implicit ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift

    val count = if (IOPlatform.isJVM) 10000 else 1000
    val p = Promise[Int]()

    val tasks = (0 until count).map(_ => IO.never: IO[Int])
    val all = tasks.foldLeft(IO.never: IO[Int])((acc, t) =>
      IO.race(acc, t).map {
        case Left(l)  => l
        case Right(r) => r
      }
    )

    val f = IO
      .race(IO.fromFuture(IO.pure(p.future)), all)
      .map { case Left(l) => l; case Right(r) => r }
      .unsafeToFuture()

    ec.tick()
    p.success(1)
    ec.tick()

    f.value shouldBe Some(Success(1))
  }

  testAsync("race forks execution") { implicit ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift

    val f = IO
      .race(IO(1), IO(1))
      .map { case Left(l) => l; case Right(r) => r }
      .unsafeToFuture()

    f.value shouldBe None
    ec.tickOne()
    f.value shouldBe Some(Success(1))

    ec.state.tasks.isEmpty shouldBe false
    ec.tickOne()
    ec.state.tasks.isEmpty shouldBe true
  }

  testAsync("race avoids extraneous async boundaries") { implicit ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift

    val f = IO
      .race(IO.shift *> IO(1), IO.shift *> IO(1))
      .map { case Left(l) => l; case Right(r) => r }
      .unsafeToFuture()

    f.value shouldBe None
    ec.tickOne()
    f.value shouldBe Some(Success(1))

    ec.state.tasks.isEmpty shouldBe false
    ec.tickOne()
    ec.state.tasks.isEmpty shouldBe true
  }

  testAsync("parMap2 should be stack safe") { ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift

    val count = if (IOPlatform.isJVM) 100000 else 1000
    val tasks = (0 until count).map(_ => IO(1))

    val sum = tasks.foldLeft(IO(0))((acc, t) => (acc, t).parMapN(_ + _))
    val f = sum.unsafeToFuture()
    ec.tick()
    f.value shouldBe Some(Success(count))
  }

  testAsync("parMap2 has a stack safe cancelable") { implicit ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift

    val count = if (IOPlatform.isJVM) 10000 else 1000
    val tasks = (0 until count).map(_ => IO.never: IO[Int])
    val all = tasks.foldLeft(IO.pure(0))((acc, t) => (acc, t).parMapN(_ + _))

    val p = Promise[Int]()
    val cancel = all.unsafeRunCancelable(Callback.promise(p))
    val f = p.future

    ec.tick()
    cancel.unsafeRunSync()
    ec.tick()

    assert(ec.state.tasks.isEmpty, "tasks.isEmpty")
    f.value shouldBe None
  }

  testAsync("parMap2 forks execution") { ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift

    val f = (IO(1), IO(1)).parMapN(_ + _).unsafeToFuture()
    f.value shouldBe None

    // Should take 2 async boundaries to complete
    ec.tickOne()
    f.value shouldBe None
    ec.tickOne()
    f.value shouldBe Some(Success(2))
  }

  testAsync("parMap2 avoids extraneous async boundaries") { ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift

    val f = (IO.shift *> IO(1), IO.shift *> IO(1))
      .parMapN(_ + _)
      .unsafeToFuture()

    f.value shouldBe None

    // Should take 2 async boundaries to complete
    ec.tickOne()
    f.value shouldBe None
    ec.tickOne()
    f.value shouldBe Some(Success(2))
  }

  testAsync("start forks automatically") { ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift

    val f = IO(1).start.flatMap(_.join).unsafeToFuture()
    f.value shouldBe None
    ec.tickOne()
    f.value shouldBe Some(Success(1))
  }

  testAsync("start avoids async boundaries") { ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift

    val f = (IO.shift *> IO(1)).start.flatMap(_.join).unsafeToFuture()
    f.value shouldBe None
    ec.tickOne()
    f.value shouldBe Some(Success(1))
  }

  testAsync("background cancels the action in cleanup") { ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift

    val f = Deferred[IO, Unit]
      .flatMap { started => //wait for this before closing resource
        Deferred[IO, ExitCase[Throwable]].flatMap { result =>
          val bg = started.complete(()) *> IO.never.guaranteeCase(result.complete)

          bg.background.use(_ => started.get) *> result.get
        }
      }
      .unsafeToFuture()

    ec.tick()

    f.value shouldBe Some(Success(ExitCase.Canceled))
  }

  testAsync("background allows awaiting the action") { ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift

    val f = Deferred[IO, Unit]
      .flatMap { latch =>
        val bg = latch.get *> IO.pure(42)

        bg.background.use(await => latch.complete(()) *> await)
      }
      .unsafeToFuture()

    ec.tick()

    f.value shouldBe Some(Success(42))
  }

  testAsync("cancel should wait for already started finalizers on success") { implicit ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift
    implicit val timer: Timer[IO] = ec.timer[IO]

    val fa = for {
      pa <- Deferred[IO, Unit]
      fiber <- IO.unit.guarantee(pa.complete(()) >> IO.sleep(1.second)).start
      _ <- pa.get
      _ <- fiber.cancel
    } yield ()

    val f = fa.unsafeToFuture()

    ec.tick()
    f.value shouldBe None

    ec.tick(1.second)
    f.value shouldBe Some(Success(()))
  }

  testAsync("cancel should wait for already started finalizers on failure") { implicit ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift
    implicit val timer: Timer[IO] = ec.timer[IO]

    val dummy = new RuntimeException("dummy")

    val fa = for {
      pa <- Deferred[IO, Unit]
      fiber <- IO.unit.guarantee(pa.complete(()) >> IO.sleep(1.second) >> IO.raiseError(dummy)).start
      _ <- pa.get
      _ <- fiber.cancel
    } yield ()

    val f = fa.unsafeToFuture()

    ec.tick()
    f.value shouldBe None

    ec.tick(1.second)
    f.value shouldBe Some(Success(()))
  }

  testAsync("cancel should wait for already started use finalizers") { implicit ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift
    implicit val timer: Timer[IO] = ec.timer[IO]

    val fa = for {
      pa <- Deferred[IO, Unit]
      fibA <- IO.unit
        .bracket(_ => IO.unit.guarantee(pa.complete(()) >> IO.sleep(2.second)))(_ => IO.unit)
        .start
      _ <- pa.get
      _ <- fibA.cancel
    } yield ()

    val f = fa.unsafeToFuture()

    ec.tick()
    f.value shouldBe None

    ec.tick(2.second)
    f.value shouldBe Some(Success(()))
  }

  testAsync("second cancel should wait for use finalizers") { implicit ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift
    implicit val timer: Timer[IO] = ec.timer[IO]

    val fa = for {
      pa <- Deferred[IO, Unit]
      fiber <- IO.unit
        .bracket(_ => (pa.complete(()) >> IO.never).guarantee(IO.sleep(2.second)))(_ => IO.unit)
        .start
      _ <- pa.get
      _ <- IO.race(fiber.cancel, fiber.cancel)
    } yield ()

    val f = fa.unsafeToFuture()

    ec.tick()
    f.value shouldBe None

    ec.tick(2.second)
    f.value shouldBe Some(Success(()))
  }

  testAsync("second cancel during acquire should wait for it and finalizers to complete") { implicit ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift
    implicit val timer: Timer[IO] = ec.timer[IO]

    val fa = for {
      pa <- Deferred[IO, Unit]
      fiber <- (pa.complete(()) >> IO.sleep(1.second))
        .bracket(_ => IO.unit)(_ => IO.sleep(1.second))
        .start
      _ <- pa.get
      _ <- IO.race(fiber.cancel, fiber.cancel)
    } yield ()

    val f = fa.unsafeToFuture()

    ec.tick()
    f.value shouldBe None

    ec.tick(1.second)
    f.value shouldBe None

    ec.tick(1.second)
    f.value shouldBe Some(Success(()))
  }

  testAsync("second cancel during acquire should wait for it and finalizers to complete (non-terminating)") {
    implicit ec =>
      implicit val contextShift: ContextShift[IO] = ec.ioContextShift
      implicit val timer: Timer[IO] = ec.timer[IO]

      val fa = for {
        pa <- Deferred[IO, Unit]
        fiber <- (pa.complete(()) >> IO.sleep(1.second))
          .bracket(_ => IO.unit)(_ => IO.never)
          .start
        _ <- pa.get
        _ <- IO.race(fiber.cancel, fiber.cancel)
      } yield ()

      val f = fa.unsafeToFuture()

      ec.tick()
      f.value shouldBe None

      ec.tick(1.day)
      f.value shouldBe None
  }

  testAsync("Multiple cancel should not hang (Issue #779)") { implicit ec =>
    implicit val contextShift: ContextShift[IO] = ec.ioContextShift
    implicit val timer: Timer[IO] = ec.timer[IO]

    val fa = for {
      fiber <- IO.sleep(1.second).start
      _ <- fiber.cancel
      _ <- fiber.cancel
    } yield ()

    val f = fa.unsafeToFuture()

    ec.tick()
    f.value shouldBe Some(Success(()))
  }
}

object IOTests {

  /** Implementation for testing default methods. */
  val ioEffectDefaults = new IODefaults

  /** Implementation for testing default methods. */
  def ioConcurrentEffectDefaults(implicit ec: ExecutionContext) =
    new IODefaults with ConcurrentEffect[IO] {
      implicit val cs: ContextShift[IO] = IO.contextShift(ec)
      override protected val ref = implicitly[ConcurrentEffect[IO]]

      def runCancelable[A](fa: IO[A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[CancelToken[IO]] =
        fa.runCancelable(cb)
      def start[A](fa: IO[A]): IO[Fiber[IO, A]] =
        fa.start
      def racePair[A, B](fa: IO[A], fb: IO[B]): IO[Either[(A, Fiber[IO, B]), (Fiber[IO, A], B)]] =
        IO.racePair(fa, fb)
    }

  class IODefaults extends Effect[IO] {
    protected val ref = implicitly[Effect[IO]]

    def async[A](k: ((Either[Throwable, A]) => Unit) => Unit): IO[A] =
      ref.async(k)
    def asyncF[A](k: ((Either[Throwable, A]) => Unit) => IO[Unit]): IO[A] =
      ref.asyncF(k)
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
    def runAsync[A](fa: IO[A])(cb: (Either[Throwable, A]) => IO[Unit]): SyncIO[Unit] =
      ref.runAsync(fa)(cb)
    def suspend[A](thunk: => IO[A]): IO[A] =
      ref.suspend(thunk)
    def bracketCase[A, B](acquire: IO[A])(use: A => IO[B])(release: (A, ExitCase[Throwable]) => IO[Unit]): IO[B] =
      ref.bracketCase(acquire)(use)(release)
  }
}
