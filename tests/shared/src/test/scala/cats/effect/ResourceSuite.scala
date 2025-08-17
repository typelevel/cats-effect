/*
 * Copyright 2020-2025 Typelevel
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

package cats.effect

import cats.{~>, SemigroupK, Show}
import cats.data.{Kleisli, OptionT}
import cats.effect.implicits._
import cats.effect.kernel.testkit.TestContext
import cats.effect.laws.AsyncTests
import cats.effect.testkit.TestControl
import cats.kernel.laws.discipline.MonoidTests
import cats.laws.discipline._
import cats.laws.discipline.arbitrary._
import cats.syntax.all._

import org.scalacheck.Cogen
import org.scalacheck.Prop.forAll

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.util.concurrent.atomic.AtomicBoolean

import munit.DisciplineSuite

class ResourceSuite extends BaseScalaCheckSuite with DisciplineSuite {

  private implicit def resourceShow[A]: Show[Resource[IO, A]] = Show.fromToString

  tickedProperty("releases resources in reverse order of acquisition") { implicit ticker =>
    forAll { (as: List[(Int, Either[Throwable, Unit])]) =>
      var released: List[Int] = Nil
      val r = as.traverse {
        case (a, e) =>
          Resource.make(IO(a))(a => IO { released = a :: released } *> IO.fromEither(e))
      }
      assertCompleteAs(r.use_.attempt.void, ())
      assertEquals(released, as.map(_._1))
    }
  }

  ticked("makes acquires non interruptible") { implicit ticker =>
    assertCompleteAs(
      IO.ref(false).flatMap { interrupted =>
        val fa = IO.sleep(5.seconds).onCancel(interrupted.set(true))

        Resource.make(fa)(_ => IO.unit).use_.timeout(1.second).attempt >> interrupted.get
      },
      false
    )
  }

  ticked("makes acquires non interruptible, overriding uncancelable") { implicit ticker =>
    assertCompleteAs(
      IO.ref(false).flatMap { interrupted =>
        val fa =
          IO.uncancelable { poll => poll(IO.sleep(5.seconds)).onCancel(interrupted.set(true)) }

        Resource.make(fa)(_ => IO.unit).use_.timeout(1.second).attempt >> interrupted.get
      },
      false
    )
  }

  ticked("releases resource if interruption happens during use") { implicit ticker =>
    val flag = IO.ref(false)

    assertCompleteAs(
      (flag, flag).tupled.flatMap {
        case (acquireFin, resourceFin) =>
          val action = IO.sleep(1.second).onCancel(acquireFin.set(true))
          val fin = resourceFin.set(true)
          val res = Resource.makeFull[IO, Unit](poll => poll(action))(_ => fin)

          res.surround(IO.sleep(5.seconds)).timeout(3.seconds).attempt >>
            (acquireFin.get, resourceFin.get).tupled
      },
      false -> true
    )
  }

  ticked("supports interruptible acquires") { implicit ticker =>
    val flag = IO.ref(false)

    assertCompleteAs(
      (flag, flag).tupled.flatMap {
        case (acquireFin, resourceFin) =>
          val action = IO.sleep(5.seconds).onCancel(acquireFin.set(true))
          val fin = resourceFin.set(true)
          val res = Resource.makeFull[IO, Unit](poll => poll(action))(_ => fin)

          res.use_.timeout(1.second).attempt >>
            (acquireFin.get, resourceFin.get).tupled
      },
      true -> false
    )
  }

  ticked("supports interruptible acquires, respecting uncancelable") { implicit ticker =>
    val flag = IO.ref(false)
    val sleep = IO.sleep(1.second)
    val timeout = 500.millis

    assertCompleteAs(
      (flag, flag, flag, flag).tupled.flatMap {
        case (acquireFin, resourceFin, a, b) =>
          val io = IO.uncancelable { poll =>
            sleep.onCancel(a.set(true)) >> poll(sleep).onCancel(b.set(true))
          }

          val resource = Resource.makeFull[IO, Unit] { poll =>
            poll(io).onCancel(acquireFin.set(true))
          }(_ => resourceFin.set(true))

          resource.use_.timeout(timeout).attempt >>
            List(a.get, b.get, acquireFin.get, resourceFin.get).sequence
      },
      List(false, true, true, false)
    )
  }

  ticked("release is always uninterruptible") { implicit ticker =>
    val flag = IO.ref(false)
    val sleep = IO.sleep(1.second)
    val timeout = 500.millis

    assertCompleteAs(
      flag.flatMap { releaseComplete =>
        val release = sleep >> releaseComplete.set(true)

        val resource = Resource.applyFull[IO, Unit] { poll => IO(() -> (_ => poll(release))) }

        resource.use_.timeout(timeout).attempt >> releaseComplete.get
      },
      true
    )
  }

  tickedProperty("eval") { implicit ticker =>
    forAll { (fa: IO[String]) => assertEqv(Resource.eval(fa).use(IO.pure), fa) }
  }

  ticked("eval - interruption") { implicit ticker =>
    def resource(d: Deferred[IO, Int]): Resource[IO, Unit] =
      for {
        _ <- Resource.make(IO.unit)(_ => d.complete(1).void)
        _ <- Resource.eval(IO.never[Unit])
      } yield ()

    def p =
      for {
        d <- Deferred[IO, Int]
        r = resource(d).use_
        fiber <- r.start
        _ <- IO.sleep(200.millis)
        _ <- fiber.cancel
        res <- d.get
      } yield res

    assertCompleteAs(p, 1)
  }

  tickedProperty("eval(fa) <-> liftK.apply(fa)") { implicit ticker =>
    forAll { (fa: IO[String], f: String => IO[Int]) =>
      assertEqv(Resource.eval(fa).use(f), Resource.liftK[IO].apply(fa).use(f))
    }
  }

  tickedProperty("evalMap") { implicit ticker =>
    forAll { (f: Int => IO[Int]) =>
      assertEqv(Resource.eval(IO(0)).evalMap(f).use(IO.pure), f(0))
    }
  }

  ticked("evalMap with error fails during use") { implicit ticker =>
    case object Foo extends Exception

    assertFailAs(
      Resource.eval(IO.unit).evalMap(_ => IO.raiseError[Unit](Foo)).use(IO.pure),
      Foo)
  }

  tickedProperty("evalTap") { implicit ticker =>
    forAll { (f: Int => IO[Int]) =>
      assertEqv(Resource.eval(IO(0)).evalTap(f).use(IO.pure), f(0).as(0))
    }
  }

  ticked("evalTap with error fails during use") { implicit ticker =>
    case object Foo extends Exception

    assertFailAs(Resource.eval(IO(0)).evalTap(_ => IO.raiseError(Foo)).void.use(IO.pure), Foo)
  }

  ticked("releases resources that implement AutoCloseable") { implicit ticker =>
    var closed = false
    val autoCloseable = new AutoCloseable {
      override def close(): Unit = closed = true
    }

    assertCompleteAs(
      Resource.fromAutoCloseable(IO(autoCloseable)).surround("Hello world".pure[IO]),
      "Hello world")

    assert(closed)
  }

  real("allocated releases two resources") {
    var a = false
    var b = false

    val test =
      Resource.make(IO.unit)(_ => IO { a = true }) >>
        Resource.make(IO.unit)(_ => IO { b = true })

    test.allocated.flatMap(_._2) >> IO {
      assert(a)
      assert(b)
    }
  }

  tickedProperty("allocated releases resources in reverse order of acquisition") {
    implicit ticker =>
      forAll { (as: List[(Int, Either[Throwable, Unit])]) =>
        var released: List[Int] = Nil
        val r = as.traverse {
          case (a, e) =>
            Resource.make(IO(a))(a => IO { released = a :: released } *> IO.fromEither(e))
        }
        assertCompleteAs(r.allocated.flatMap(_._2).attempt.void, ())
        assertEquals(released, as.map(_._1))
      }
  }

  ticked("allocated does not release until close is invoked") { implicit ticker =>
    val released = new java.util.concurrent.atomic.AtomicBoolean(false)
    val release = Resource.make(IO.unit)(_ => IO(released.set(true)))
    val resource = Resource.eval(IO.unit)

    // do not inline: it confuses Dotty
    val ioa = (release *> resource).allocated

    val prog = for {
      res <- ioa
      (_, close) = res
      _ <- IO(assert(!released.get()))
      _ <- close
      _ <- IO(assert(released.get()))
    } yield ()

    assertCompleteAs(prog, ())
  }

  tickedProperty("mapK") { implicit ticker =>
    forAll { (fa: Kleisli[IO, Int, Int]) =>
      val runWithTwo = new ~>[Kleisli[IO, Int, *], IO] {
        override def apply[A](fa: Kleisli[IO, Int, A]): IO[A] = fa(2)
      }
      assertEqv(Resource.eval(fa).mapK(runWithTwo).use(IO.pure), fa(2))
    }
  }

  real("attempt on Resource after mapK") {
    class Err extends Exception

    Resource
      .eval(IO.raiseError[Int](new Err))
      .mapK(Kleisli.liftK[IO, Int])
      .attempt
      .surround(3.pure[Kleisli[IO, Int, *]])
      .run(0)
      .mustEqual(3)
  }

  ticked("mapK should preserve ExitCode-specific behaviour") { implicit ticker =>
    def sideEffectyResource: (AtomicBoolean, Resource[IO, Unit]) = {
      val cleanExit = new java.util.concurrent.atomic.AtomicBoolean(false)
      val res = Resource.makeCase(IO.unit) {
        case (_, Resource.ExitCase.Succeeded) =>
          IO {
            cleanExit.set(true)
          }
        case _ => IO.unit
      }
      (cleanExit, res)
    }

    val (clean, res) = sideEffectyResource
    assertCompleteAs(res.use(_ => IO.unit).attempt.void, ())
    assert(clean.get())

    val (clean1, res1) = sideEffectyResource
    assertCompleteAs(res1.use(_ => IO.raiseError(new Throwable("oh no"))).attempt.void, ())
    assert(!clean1.get())

    val (clean2, res2) = sideEffectyResource
    assertCompleteAs(res2.mapK(Kleisli.liftK[IO, Int]).use_.run(0).attempt.void, ())
    assert(clean2.get())

    val (clean3, res3) = sideEffectyResource
    assertCompleteAs(
      res3
        .mapK(Kleisli.liftK[IO, Int])
        .use(_ => Kleisli.liftF(IO.raiseError[Unit](new Throwable("oh no"))))
        .run(0)
        .attempt
        .void,
      ())
    assert(!clean3.get())
  }

  ticked("mapK respects interruptible acquires") { implicit ticker =>
    val flag = IO.ref(false)
    val sleep = IO.sleep(1.second)
    val timeout = 500.millis

    def fa =
      (flag, flag).tupled.flatMap {
        case (a, b) =>
          val io = IO.uncancelable { poll =>
            sleep.onCancel(a.set(true)) >> poll(sleep).onCancel(b.set(true))
          }

          val resource = Resource.makeFull[IO, Unit](poll => poll(io))(_ => IO.unit)

          val mapKd = resource.mapK(Kleisli.liftK[IO, Int])

          mapKd.use_.timeout(timeout).run(0).attempt >> (a.get, b.get).tupled
      }

    assertCompleteAs(fa, false -> true)
  }

  tickedProperty("allocated produces the same value as the resource") { implicit ticker =>
    forAll { (resource: Resource[IO, Int]) =>
      val a0 = IO.uncancelable { p =>
        p(resource.allocated).flatMap { case (b, fin) => fin.as(b) }
      }
      val a1 = resource.use(IO.pure)

      assertEqv(
        a0.flatMap(IO.pure).handleErrorWith(IO.raiseError),
        a1.flatMap(IO.pure).handleErrorWith(IO.raiseError))
    }
  }

  ticked("allocated does not release until close is invoked on mapK'd Resources") {
    implicit ticker =>
      val released = new java.util.concurrent.atomic.AtomicBoolean(false)

      val runWithTwo = new ~>[Kleisli[IO, Int, *], IO] {
        override def apply[A](fa: Kleisli[IO, Int, A]): IO[A] = fa(2)
      }
      val takeAnInteger = new ~>[IO, Kleisli[IO, Int, *]] {
        override def apply[A](fa: IO[A]): Kleisli[IO, Int, A] = Kleisli.liftF(fa)
      }
      val plusOne = Kleisli { (i: Int) => IO(i + 1) }
      val plusOneResource = Resource.eval(plusOne)

      val release = Resource.make(IO.unit)(_ => IO(released.set(true)))
      val resource = Resource.eval(IO.unit)

      // do not inline: it confuses Dotty
      val ioa = ((release *> resource).mapK(takeAnInteger) *> plusOneResource)
        .mapK(runWithTwo)
        .allocated

      val prog = for {
        res <- ioa
        (_, close) = res
        _ <- IO(assert(!released.get()))
        _ <- close
        _ <- IO(assert(released.get()))
      } yield ()

      assertCompleteAs(prog, ())
  }

  ticked("use is stack-safe over binds") { implicit ticker =>
    val r = (1 to 10000)
      .foldLeft(Resource.eval(IO.unit)) {
        case (r, _) =>
          r.flatMap(_ => Resource.eval(IO.unit))
      }
      .use_
    assertEqv(r, IO.unit)
  }

  real("use is stack-safe over binds - 2") {
    val n = 50000
    def p(i: Int = 0, n: Int = 50000): Resource[IO, Int] =
      Resource
        .pure {
          if (i < n) Left(i + 1)
          else Right(i)
        }
        .flatMap {
          case Left(a) => p(a)
          case Right(b) => Resource.pure(b)
        }

    p(n = n).use(IO.pure).mustEqual(n)
  }

  ticked("mapK is stack-safe over binds") { implicit ticker =>
    val r = (1 to 10000)
      .foldLeft(Resource.eval(IO.unit)) {
        case (r, _) =>
          r.flatMap(_ => Resource.eval(IO.unit))
      }
      .mapK {
        new ~>[IO, IO] {
          def apply[A](a: IO[A]): IO[A] = a
        }
      }
      .use_

    assertEqv(r, IO.unit)
  }

  ticked("attempt is stack-safe over binds") { implicit ticker =>
    val r = (1 to 10000)
      .foldLeft(Resource.eval(IO.unit)) {
        case (r, _) =>
          r.flatMap(_ => Resource.eval(IO.unit))
      }
      .attempt

    assertCompleteAs(r.use_, ())
  }

  ticked("safe attempt suspended resource") { implicit ticker =>
    val exception = new Exception("boom!")
    val suspend = Resource.suspend[IO, Unit](IO.raiseError(exception))
    assertFailAs(suspend.use_, exception)
  }

  tickedProperty("both - releases resources in reverse order of acquisition") {
    implicit ticker =>
      // conceptually asserts that:
      //   forAll (r: Resource[F, A]) then r <-> r.both(Resource.unit) <-> Resource.unit.both(r)
      // needs to be tested manually to assert the equivalence during cleanup as well
      forAll { (as: List[(Int, Either[Throwable, Unit])], rhs: Boolean) =>
        var released: List[Int] = Nil
        val r = as.traverse {
          case (a, e) =>
            Resource.make(IO(a))(a => IO { released = a :: released } *> IO.fromEither(e))
        }
        val unit = ().pure[Resource[IO, *]]
        val p = if (rhs) r.both(unit) else unit.both(r)

        assertCompleteAs(p.use_.attempt.void, ())
        assertEquals(released, as.map(_._1))
      }
  }

  ticked("both - parallel acquisition and release") { implicit ticker =>
    var leftAllocated = false
    var rightAllocated = false
    var leftReleasing = false
    var rightReleasing = false
    var leftReleased = false
    var rightReleased = false

    val wait = IO.sleep(1.second)
    val lhs = Resource.make(wait >> IO { leftAllocated = true }) { _ =>
      IO { leftReleasing = true } >> wait >> IO { leftReleased = true }
    }
    val rhs = Resource.make(wait >> IO { rightAllocated = true }) { _ =>
      IO { rightReleasing = true } >> wait >> IO { rightReleased = true }
    }

    lhs.both(rhs).use(_ => wait).unsafeToFuture()

    // after 1 second:
    //  both resources have allocated (concurrency, serially it would happen after 2 seconds)
    //  resources are still open during `use` (correctness)
    ticker.ctx.tick()
    ticker.ctx.advanceAndTick(1.second)
    assert(leftAllocated)
    assert(rightAllocated)
    assert(!leftReleasing)
    assert(!rightReleasing)

    // after 2 seconds:
    //  both resources have started cleanup (correctness)
    ticker.ctx.advanceAndTick(1.second)
    assert(leftReleasing)
    assert(rightReleasing)
    assert(!leftReleased)
    assert(!rightReleased)

    // after 3 seconds:
    //  both resources have terminated cleanup (concurrency, serially it would happen after 4 seconds)
    ticker.ctx.advanceAndTick(1.second)
    assert(leftReleased)
    assert(rightReleased)
  }

  ticked("both - safety: lhs error during rhs interruptible region") { implicit ticker =>
    var leftAllocated = false
    var rightAllocated = false
    var leftReleasing = false
    var rightReleasing = false
    var leftReleased = false
    var rightReleased = false

    def wait(n: Int) = IO.sleep(n.seconds)
    val lhs = for {
      _ <- Resource.make(wait(1) >> IO { leftAllocated = true }) { _ =>
        IO { leftReleasing = true } >> wait(1) >> IO { leftReleased = true }
      }
      _ <- Resource.eval(wait(1) >> IO.raiseError[Unit](new Exception))
    } yield ()

    val rhs = for {
      _ <- Resource.make(wait(1) >> IO { rightAllocated = true }) { _ =>
        IO { rightReleasing = true } >> wait(1) >> IO { rightReleased = true }
      }
      _ <- Resource.eval(wait(2))
    } yield ()

    lhs.both(rhs).use(_ => IO.unit).handleError(_ => ()).unsafeToFuture()

    // after 1 second:
    //  both resources have allocated (concurrency, serially it would happen after 2 seconds)
    //  resources are still open during `flatMap` (correctness)
    ticker.ctx.tick()
    ticker.ctx.advanceAndTick(1.second)
    assert(leftAllocated)
    assert(rightAllocated)
    assert(!leftReleasing)
    assert(!rightReleasing)

    // after 2 seconds:
    //  both resources have started cleanup (interruption, or rhs would start releasing after 3 seconds)
    ticker.ctx.advanceAndTick(1.second)
    assert(leftReleasing)
    assert(rightReleasing)
    assert(!leftReleased)
    assert(!rightReleased)

    // after 3 seconds:
    //  both resources have terminated cleanup (concurrency, serially it would happen after 4 seconds)
    ticker.ctx.advanceAndTick(1.second)
    assert(leftReleased)
    assert(rightReleased)
  }

  ticked("both - safety: rhs error during lhs uninterruptible region") { implicit ticker =>
    var leftAllocated = false
    var rightAllocated = false
    var rightErrored = false
    var leftReleasing = false
    var rightReleasing = false
    var leftReleased = false
    var rightReleased = false

    def wait(n: Int) = IO.sleep(n.seconds)
    val lhs = Resource.make(wait(3) >> IO { leftAllocated = true }) { _ =>
      IO { leftReleasing = true } >> wait(1) >> IO { leftReleased = true }
    }
    val rhs = for {
      _ <- Resource.make(wait(1) >> IO { rightAllocated = true }) { _ =>
        IO { rightReleasing = true } >> wait(1) >> IO { rightReleased = true }
      }
      _ <- Resource.make(
        wait(1) >> IO { rightErrored = true } >> IO.raiseError[Unit](new Exception))(_ =>
        IO.unit)
    } yield ()

    lhs.both(rhs).use(_ => wait(1)).handleError(_ => ()).unsafeToFuture()

    // after 1 second:
    //  rhs has partially allocated, lhs executing
    ticker.ctx.tick()
    ticker.ctx.advanceAndTick(1.second)
    assert(!leftAllocated)
    assert(rightAllocated)
    assert(!rightErrored)
    assert(!leftReleasing)
    assert(!rightReleasing)

    // after 2 seconds:
    //  rhs has failed, release blocked since lhs is in uninterruptible allocation
    ticker.ctx.advanceAndTick(1.second)
    assert(!leftAllocated)
    assert(rightAllocated)
    assert(rightErrored)
    assert(!leftReleasing)
    assert(!rightReleasing)

    // after 3 seconds:
    //  lhs completes allocation (concurrency, serially it would happen after 4 seconds)
    //  both resources have started cleanup (correctness, error propagates to both sides)
    ticker.ctx.advanceAndTick(1.second)
    assert(leftAllocated)
    assert(leftReleasing)
    assert(rightReleasing)
    assert(!leftReleased)
    assert(!rightReleased)

    // after 4 seconds:
    //  both resource have terminated cleanup (concurrency, serially it would happen after 5 seconds)
    ticker.ctx.advanceAndTick(1.second)
    assert(leftReleased)
    assert(rightReleased)
  }

  import Resource.ExitCase

  ticked("both - propagate the exit case - use successfully, test left") { implicit ticker =>
    var got: ExitCase = null
    val r = Resource.onFinalizeCase(ec => IO { got = ec })
    assertCompleteAs(r.both(Resource.unit).use(_ => IO.unit), ())
    assertEquals(got, ExitCase.Succeeded)
  }

  ticked("both - propagate the exit case - use successfully, test right") { implicit ticker =>
    var got: ExitCase = null
    val r = Resource.onFinalizeCase(ec => IO { got = ec })
    assertCompleteAs(Resource.unit.both(r).use(_ => IO.unit), ())
    assertEquals(got, ExitCase.Succeeded)
  }

  ticked("both - propagate the exit case - use errored, test left") { implicit ticker =>
    var got: ExitCase = null
    val ex = new Exception
    val r = Resource.onFinalizeCase(ec => IO { got = ec })
    assertFailAs(r.both(Resource.unit).use(_ => IO.raiseError(ex)), ex)
    assertEquals(got, ExitCase.Errored(ex))
  }

  ticked("both - propagate the exit case - use errored, test right") { implicit ticker =>
    var got: ExitCase = null
    val ex = new Exception
    val r = Resource.onFinalizeCase(ec => IO { got = ec })
    assertFailAs(Resource.unit.both(r).use(_ => IO.raiseError(ex)), ex)
    assertEquals(got, ExitCase.Errored(ex))
  }

  ticked("both - propagate the exit case - right errored, test left") { implicit ticker =>
    var got: ExitCase = null
    val ex = new Exception
    val r = Resource.onFinalizeCase(ec => IO { got = ec })
    assertFailAs(r.both(Resource.eval(IO.sleep(1.second) *> IO.raiseError(ex))).use_, ex)
    assertEquals(got, ExitCase.Errored(ex))
  }

  ticked("both - propagate the exit case - left errored, test right") { implicit ticker =>
    var got: ExitCase = null
    val ex = new Exception
    val r = Resource.onFinalizeCase(ec => IO { got = ec })
    assertFailAs(Resource.eval(IO.sleep(1.second) *> IO.raiseError(ex)).both(r).use_, ex)
    assertEquals(got, ExitCase.Errored(ex))
  }

  ticked("both - propagate the exit case - use canceled, test left") { implicit ticker =>
    var got: ExitCase = null
    val r = Resource.onFinalizeCase(ec => IO { got = ec })
    assertSelfCancel(r.both(Resource.unit).use(_ => IO.canceled))
    assertEquals(got, ExitCase.Canceled)
  }

  ticked("both - propagate the exit case - use canceled, test right") { implicit ticker =>
    var got: ExitCase = null
    val r = Resource.onFinalizeCase(ec => IO { got = ec })
    assertSelfCancel(Resource.unit.both(r).use(_ => IO.canceled))
    assertEquals(got, ExitCase.Canceled)
  }

  ticked("both - propagate the exit case - right canceled, test left") { implicit ticker =>
    var got: ExitCase = null
    val r = Resource.onFinalizeCase(ec => IO { got = ec })
    assertSelfCancel(r.both(Resource.eval(IO.sleep(1.second) *> IO.canceled)).use_)
    assertEquals(got, ExitCase.Canceled)
  }

  ticked("both - propagate the exit case - left canceled, test right") { implicit ticker =>
    var got: ExitCase = null
    val r = Resource.onFinalizeCase(ec => IO { got = ec })
    assertSelfCancel(Resource.eval(IO.sleep(1.second) *> IO.canceled).both(r).use_)
    assertEquals(got, ExitCase.Canceled)
  }

  ticked("releases both resources on combineK") { implicit ticker =>
    var acquired: Set[Int] = Set.empty
    var released: Set[Int] = Set.empty
    def observe(a: Int) =
      Resource.make(IO(acquired += a).as(a))(a => IO(released += a))

    assertCompleteAs(observe(1).combineK(observe(2)).use_.attempt.void, ())
    assertEquals(released, acquired)
  }

  ticked(
    "releases both resources on combineK when using a SemigroupK instance that discards allocated values") {
    implicit ticker =>
      implicit val sgk: SemigroupK[IO] = new SemigroupK[IO] {
        override def combineK[A](x: IO[A], y: IO[A]): IO[A] = x <* y
      }
      var acquired: Set[Int] = Set.empty
      var released: Set[Int] = Set.empty
      def observe(a: Int) =
        Resource.make(IO(acquired += a) *> IO.pure(a))(a => IO(released += a))

      assertCompleteAs(observe(1).combineK(observe(2)).use_.attempt.void, ())
      assertEquals(released, acquired)
  }

  tickedProperty("combineK - behave like orElse when underlying effect does") {
    implicit ticker =>
      forAll { (r1: Resource[IO, Int], r2: Resource[IO, Int]) =>
        val lhs = r1.orElse(r2)
        val rhs = r1 <+> r2

        assertEqv(lhs, rhs)
      }
  }

  tickedProperty("combineK - behave like underlying effect") { implicit ticker =>
    forAll { (ot1: OptionT[IO, Int], ot2: OptionT[IO, Int]) =>
      val lhs = Resource.eval(ot1 <+> ot2).use(OptionT.pure[IO](_)).value
      val rhs = (Resource.eval(ot1) <+> Resource.eval(ot2)).use(OptionT.pure[IO](_)).value

      assertEqv(lhs, rhs)
    }
  }

  ticked("combineK - propagate the exit case - use successfully, test left") {
    implicit ticker =>
      var got: ExitCase = null
      val r = Resource.onFinalizeCase(ec => IO { got = ec })
      assertCompleteAs(r.combineK(Resource.unit).use(_ => IO.unit), ())
      assertEquals(got, ExitCase.Succeeded)
  }

  ticked("combineK - propagate the exit case - use errored, test left") { implicit ticker =>
    var got: ExitCase = null
    val ex = new Exception
    val r = Resource.onFinalizeCase(ec => IO { got = ec })
    assertFailAs(r.combineK(Resource.unit).use(_ => IO.raiseError(ex)), ex)
    assertEquals(got, ExitCase.Errored(ex))
  }

  ticked("combineK - propagate the exit case - left errored, test left") { implicit ticker =>
    var got: ExitCase = null
    val ex = new Exception
    val r = Resource.onFinalizeCase(ec => IO { got = ec }) *>
      Resource.eval(IO.raiseError(ex))
    assertCompleteAs(r.combineK(Resource.unit).use_, ())
    assertEquals(got, ExitCase.Succeeded)
  }

  ticked("combineK - propagate the exit case - left errored, test right") { implicit ticker =>
    var got: ExitCase = null
    val ex = new Exception
    val r = Resource.onFinalizeCase(ec => IO { got = ec })
    assertCompleteAs(Resource.eval(IO.raiseError(ex)).combineK(r).use_, ())
    assertEquals(got, ExitCase.Succeeded)
  }

  ticked("combineK - propagate the exit case - left errored, use errored, test right") {
    implicit ticker =>
      var got: ExitCase = null
      val ex = new Exception
      val r = Resource.onFinalizeCase(ec => IO { got = ec })
      assertFailAs(
        Resource.eval(IO.raiseError(new Exception)).combineK(r).use(_ => IO.raiseError(ex)),
        ex)
      assertEquals(got, ExitCase.Errored(ex))
  }

  ticked("combineK - propagate the exit case - use canceled, test left") { implicit ticker =>
    var got: ExitCase = null
    val r = Resource.onFinalizeCase(ec => IO { got = ec })
    assertSelfCancel(r.combineK(Resource.unit).use(_ => IO.canceled))
    assertEquals(got, ExitCase.Canceled)
  }

  ticked("combineK - propagate the exit case - left errored, use canceled, test right") {
    implicit ticker =>
      var got: ExitCase = null
      val r = Resource.onFinalizeCase(ec => IO { got = ec })
      assertSelfCancel(
        Resource.eval(IO.raiseError(new Exception)).combineK(r).use(_ => IO.canceled))
      assertEquals(got, ExitCase.Canceled)
  }

  ticked("surround - wrap an effect in a usage and ignore the value produced by resource") {
    implicit ticker =>
      val r = Resource.eval(IO.pure(0))
      val surroundee = IO("hello")
      val surrounded = r.surround(surroundee)

      assertEqv(surrounded, surroundee)
  }

  ticked(
    "surroundK - wrap an effect in a usage, ignore the value produced by resource and return FunctionK") {
    implicit ticker =>
      val r = Resource.eval(IO.pure(0))
      val surroundee = IO("hello")
      val surround = r.surroundK
      val surrounded = surround(surroundee)

      assertEqv(surrounded, surroundee)
  }

  tickedProperty("evalOn - run acquire and release on provided ExecutionContext") {
    implicit ticker =>
      forAll { (executionContext: ExecutionContext) =>
        val assertion =
          IO.executionContext.flatMap(ec => IO(assertEquals(ec, executionContext)).void)
        Resource.make(assertion)(_ => assertion).evalOn(executionContext).use_.as(true)
      }
  }

  {
    val wait = IO.sleep(1.second)
    val waitR = Resource.eval(wait)

    ticked("Async[Resource] - onCancel - not fire when adjacent to uncancelable") {
      implicit ticker =>
        var fired = false

        val test =
          Resource.eval(IO.canceled).uncancelable.onCancel(Resource.eval(IO { fired = true }))

        test.use_.unsafeToFuture()
        ticker.ctx.tick()

        assert(!fired)
    }

    ticked("Async[Resource] - async - forward inner finalizers into outer scope") {
      implicit ticker =>
        var innerClosed = false
        var outerClosed = false

        val inner = Resource.make(wait)(_ => IO { innerClosed = true })
        val outerInit = Resource.make(IO.unit)(_ => IO { outerClosed = true })

        val async = Async[Resource[IO, *]].async[Unit] { cb =>
          (inner *> Resource.eval(IO(cb(Right(()))))).map(_ => None)
        }

        (outerInit *> async *> waitR).use_.unsafeToFuture()

        ticker.ctx.tick()
        ticker.ctx.advanceAndTick(1.second)
        assert(!innerClosed)
        assert(!outerClosed)

        ticker.ctx.advanceAndTick(1.second)
        assert(innerClosed)
        assert(outerClosed)
    }

    ticked("Async[Resource] - forceR - closes left scope") { implicit ticker =>
      var leftClosed = false
      var rightClosed = false

      val target =
        Resource.make(wait)(_ => IO { leftClosed = true }) !>
          Resource.make(wait)(_ => IO { rightClosed = true })

      target.use_.unsafeToFuture()

      ticker.ctx.tick()
      ticker.ctx.advanceAndTick(1.second)
      assert(leftClosed)
      assert(!rightClosed)

      ticker.ctx.advanceAndTick(1.second)
      assert(rightClosed)
    }

    ticked("Async[Resource] - onCancel - catches inner cancelation") { implicit ticker =>
      var innerClosed = false
      var outerClosed = false
      var canceled = false

      val inner =
        Resource.make(IO.unit)(_ => IO { innerClosed = true }) *> Resource.eval(IO.canceled)
      val outer = Resource.make(IO.unit)(_ => IO { outerClosed = true })

      val target = inner.onCancel(Resource.eval(IO { canceled = true })) *> outer

      assertSelfCancel(target.use_)
      assert(innerClosed)
      assert(!outerClosed)
      assert(canceled)
    }

    ticked("Async[Resource] - onCancel - does not extend across the region") {
      implicit ticker =>
        var innerClosed = false
        var outerClosed = false
        var canceled = false

        val inner = Resource.make(IO.unit)(_ => IO { innerClosed = true })
        val outer =
          Resource.make(IO.unit)(_ => IO { outerClosed = true }) *> Resource.eval(IO.canceled)

        val target = inner.onCancel(Resource.eval(IO { canceled = true })) *> outer

        assertSelfCancel(target.use_)
        assert(innerClosed)
        assert(outerClosed)
        assert(
          !canceled
        ) // if this is true, it means the scope was extended rather than being closed!
    }

    ticked(
      "Async[Resource] - race - two acquisitions, closing the loser and maintaining the winner") {
      implicit ticker =>
        var winnerClosed = false
        var loserClosed = false
        var completed = false

        var results: Either[String, String] = null

        val winner = Resource
          .make(IO.unit)(_ => IO { winnerClosed = true })
          .evalMap(_ => IO.sleep(100.millis))
          .map(_ => "winner")
        val loser = Resource
          .make(IO.unit)(_ => IO { loserClosed = true })
          .evalMap(_ => IO.sleep(200.millis))
          .map(_ => "loser")

        val target =
          winner.race(loser).evalMap(e => IO { results = e }) *> waitR *> Resource.eval(IO {
            completed = true
          })

        target.use_.unsafeToFuture()

        ticker.ctx.tick()
        ticker.ctx.advanceAndTick(50.millis)
        assert(!winnerClosed)
        assert(!loserClosed)
        assert(!completed)
        assert(results eq null)

        ticker.ctx.advanceAndTick(50.millis)
        assert(!winnerClosed)
        assert(loserClosed)
        assert(!completed)
        assertEquals(results, Left("winner"))

        ticker.ctx.advanceAndTick(50.millis)
        assert(!winnerClosed)
        assert(loserClosed)
        assert(!completed)
        assertEquals(results, Left("winner"))

        ticker.ctx.advanceAndTick(1.second)
        assert(winnerClosed)
        assert(completed)
    }

    real("Async[Resource] - race - closes the loser eagerly") {
      val go = (
        IO.ref(false),
        IO.ref(false),
        IO.deferred[Either[Unit, Unit]]
      ).flatMapN { (acquiredLeft, acquiredRight, loserReleased) =>
        val left = Resource.make(acquiredLeft.set(true))(_ =>
          loserReleased.complete(Left[Unit, Unit](())).void)

        val right = Resource.make(acquiredRight.set(true))(_ =>
          loserReleased.complete(Right[Unit, Unit](())).void)

        Resource.race(left, right).use {
          case Left(()) =>
            acquiredRight.get.ifM(loserReleased.get.map(v => assert(v.isRight)), IO.unit)
          case Right(()) =>
            acquiredLeft.get.ifM(loserReleased.get.map(v => assert(v.isLeft)), IO.unit)
        }
      }

      TestControl.executeEmbed(go.replicateA_(100))
    }

    ticked("Async[Resource] - start - runs fibers in parallel") { implicit ticker =>
      var completed = false
      val target = waitR.start *> waitR *> Resource.eval(IO { completed = true })

      target.use_.unsafeToFuture()
      ticker.ctx.tickAll()
      assert(completed)
    }

    ticked(
      "Async[Resource] - start - run finalizers when completed and outer scope is closed") {
      implicit ticker =>
        var i = 0
        var completed = false
        val target = Resource.make(IO { completed = true })(_ => IO(i += 1)).start *> waitR

        assertCompleteAs(target.use_, ())
        assert(completed)
        assertEquals(i, 1)
    }

    ticked(
      "Async[Resource] - start - run finalizers after completion when outer scope is closed") {
      implicit ticker =>
        var i = 0
        var completed = false

        val finish = Resource.eval(IO { completed = true })
        val target = Resource.make(wait)(_ => IO(i += 1)).start *> finish

        target.use_.unsafeToFuture()
        ticker.ctx.advanceAndTick(50.millis)

        assert(completed)
        assertEquals(i, 0)

        ticker.ctx.advanceAndTick(1.second)
        assertEquals(i, 1)
    }

    ticked("Async[Resource] - start - run finalizers once when canceled") { implicit ticker =>
      var i = 0

      val fork = Resource.make(IO.unit)(_ => IO(i += 1)) *> waitR *> waitR
      val target = fork.start flatMap { f => waitR *> f.cancel }

      target.use_.unsafeToFuture()

      ticker.ctx.tick()
      ticker.ctx.advanceAndTick(1.second)
      assertEquals(i, 1)

      ticker.ctx.advanceAndTick(1.second)
      assertEquals(i, 1)
    }

    ticked("Async[Resource] - start - join scope contained by outer") { implicit ticker =>
      var i = 0
      var completed = false

      val fork = Resource.make(IO.unit)(_ => IO(i += 1))

      val target =
        fork.start.evalMap(f => (f.joinWithNever *> waitR).use_) *>
          waitR *>
          Resource.eval(IO { completed = true })

      target.use_.unsafeToFuture()

      ticker.ctx.tick()
      ticker.ctx.advanceAndTick(100.millis)
      assertEquals(i, 0)

      ticker.ctx.advanceAndTick(900.millis)
      assertEquals(i, 0)
      assert(!completed)

      ticker.ctx.advanceAndTick(1.second)
      assertEquals(i, 1)
      assert(completed)
    }
  }

  {
    ticked("Concurrent[Resource] - both - parallel acquisition and release") {
      implicit ticker =>
        var leftAllocated = false
        var rightAllocated = false
        var leftReleasing = false
        var rightReleasing = false
        var leftReleased = false
        var rightReleased = false

        val wait = IO.sleep(1.second)
        val lhs = Resource.make(wait >> IO { leftAllocated = true }) { _ =>
          IO { leftReleasing = true } >> wait >> IO { leftReleased = true }
        }
        val rhs = Resource.make(wait >> IO { rightAllocated = true }) { _ =>
          IO { rightReleasing = true } >> wait >> IO { rightReleased = true }
        }

        Async[Resource[IO, *]].both(lhs, rhs).use(_ => wait).unsafeToFuture()

        // after 1 second:
        //  both resources have allocated (concurrency, serially it would happen after 2 seconds)
        //  resources are still open during `use` (correctness)
        ticker.ctx.tick()
        ticker.ctx.advanceAndTick(1.second)
        assert(leftAllocated)
        assert(rightAllocated)
        assert(!leftReleasing)
        assert(!rightReleasing)

        // after 2 seconds:
        //  both resources have started cleanup (correctness)
        ticker.ctx.advanceAndTick(1.second)
        assert(leftReleasing)
        assert(rightReleasing)
        assert(!leftReleased)
        assert(!rightReleased)

        // after 3 seconds:
        //  both resources have terminated cleanup (concurrency, serially it would happen after 4 seconds)
        ticker.ctx.advanceAndTick(1.second)
        assert(leftReleased)
        assert(rightReleased)
    }

    tickedProperty("Concurrent[Resource] - memoize - memoize and then flatten is identity") {
      implicit ticker => forAll { (r: Resource[IO, Int]) => assertEqv(r.memoize.flatten, r) }
    }
    ticked("Concurrent[Resource] - memoize - allocates once and releases at end") {
      implicit ticker =>
        assertCompleteAs(
          (IO.ref(0), IO.ref(0))
            .mapN { (acquired, released) =>
              val r = Resource.make(acquired.update(_ + 1).void)(_ => released.update(_ + 1))
              def acquiredMustBe(i: Int) = acquired.get.map(v => assertEquals(v, i)).void
              def releasedMustBe(i: Int) = released.get.map(v => assertEquals(v, i)).void
              r.memoize.use { memo =>
                acquiredMustBe(0) *> releasedMustBe(0) *>
                  memo.surround(acquiredMustBe(1) *> releasedMustBe(0)) *>
                  acquiredMustBe(1) *> releasedMustBe(0) *>
                  memo.surround(acquiredMustBe(1) *> releasedMustBe(0)) *>
                  acquiredMustBe(1) *> releasedMustBe(0)
              } *> acquiredMustBe(1) *> releasedMustBe(1)
            }
            .flatten
            .void,
          ()
        )
    }
    ticked("Concurrent[Resource] - memoize - does not allocate if not used") {
      implicit ticker =>
        assertCompleteAs(
          (IO.ref(0), IO.ref(0))
            .mapN { (acquired, released) =>
              val r = Resource.make(acquired.update(_ + 1).void)(_ => released.update(_ + 1))
              def acquiredMustBe(i: Int) = acquired.get.map(v => assertEquals(v, i)).void
              def releasedMustBe(i: Int) = released.get.map(v => assertEquals(v, i)).void
              r.memoize.surround(acquiredMustBe(0) *> releasedMustBe(0)) *>
                acquiredMustBe(0) *> releasedMustBe(0)
            }
            .flatten
            .void,
          ()
        )
    }
    ticked("Concurrent[Resource] - memoize - does not leak if canceled") { implicit ticker =>
      assertCompleteAs(
        (IO.ref(0), IO.ref(0)).flatMapN { (acquired, released) =>
          val r = Resource.make(IO.sleep(2.seconds) *> acquired.update(_ + 1).void) { _ =>
            released.update(_ + 1)
          }

          def acquiredMustBe(i: Int) = acquired.get.map(v => assertEquals(v, i)).void
          def releasedMustBe(i: Int) = released.get.map(v => assertEquals(v, i)).void

          r.memoize.use { memo =>
            memo.timeoutTo(1.second, Resource.unit[IO]).use_
          } *> acquiredMustBe(1) *> releasedMustBe(1)
        }.void,
        ()
      )
    }

    // TODO enable once `PureConc` finalizer bug is fixed.
    testUnit("does not leak if canceled right after delayed acquire is canceled".ignore) {
      import cats.effect.kernel.testkit.pure._
      type F[A] = PureConc[Throwable, A]
      val F = Concurrent[F]
      def go = for {
        acquired <- F.ref(false)
        released <- F.ref(false)
        fiber <- Resource
          .make(acquired.set(true))(_ => released.set(true))
          .memoizedAcquire
          .use(identity)
          .start
        _ <- F.cede.untilM_(acquired.get)
        _ <- fiber.cancel
        _ <- fiber.join
        acquireRun <- acquired.get
        releaseRun <- released.get
      } yield acquireRun && releaseRun

      assertEquals(run(go), Outcome.succeeded[Option, Throwable, Boolean](Some(true)))
    }
  }

  ticked("attempt - releases resource on error") { implicit ticker =>
    assertCompleteAs(
      IO.ref(0)
        .flatMap { ref =>
          val resource = Resource.make(ref.update(_ + 1))(_ => ref.update(_ + 1))
          val error = Resource.raiseError[IO, Unit, Throwable](new Exception)
          (resource *> error).attempt.use { r =>
            IO(assert(r.isLeft)) *> ref.get.map { v => assertEquals(v, 2) }
          }
        }
        .void,
      ()
    )
  }

  ticked("attempt - acquire is interruptible") { implicit ticker =>
    val sleep = IO.never
    val timeout = 500.millis
    assertCompleteAs(
      IO.ref(false).flatMap { ref =>
        val r = Resource.makeFull[IO, Unit] { poll => poll(sleep).onCancel(ref.set(true)) }(_ =>
          IO.unit)
        r.attempt.timeout(timeout).attempt.use_ *> ref.get
      },
      true
    )
  }

  real("uncancelable - does not suppress errors within use") {
    case object TestException extends RuntimeException

    for {
      slot <- IO.deferred[Resource.ExitCase]
      rsrc = Resource.makeCase(IO.unit)((_, ec) => slot.complete(ec).void)
      _ <- rsrc.uncancelable.use(_ => IO.raiseError(TestException)).handleError(_ => ())
      results <- slot.get

      _ <- IO {
        assertEquals(results, Resource.ExitCase.Errored(TestException))
      }
    } yield ()
  }

  ticked("uncancelable - use is stack-safe over binds") { implicit ticker =>
    val res = Resource.make(IO.unit)(_ => IO.unit)
    val r = (1 to 50000)
      .foldLeft(res) {
        case (r, _) =>
          r.flatMap(_ => res)
      }
      .uncancelable
      .use_

    assertEqv(r, IO.unit)
  }

  ticked("allocatedCase - is stack-safe over binds") { implicit ticker =>
    val res = Resource.make(IO.unit)(_ => IO.unit)
    val r = (1 to 50000)
      .foldLeft(res) {
        case (r, _) =>
          r.flatMap(_ => res)
      }
      .allocatedCase
      .map(_._1)

    assertEqv(r, IO.unit)
  }

  ticked("Resource[Resource[IO, *], *] - flatten with finalizers inside-out") {
    implicit ticker =>
      var results = ""

      val r: Resource[Resource[IO, *], Int] =
        Resource.make(Resource.make(IO.pure(42))(_ => IO(results += "a")))(_ =>
          Resource.eval(IO(results += "b")))

      assertCompleteAs(r.flattenK.use(IO.pure(_)), 42)
      assertEquals(results, "ab")
  }

  ticked("Resource[Resource[IO, *], *] - flattenK is stack-safe over binds") {
    implicit ticker =>
      val res = Resource.make(IO.unit)(_ => IO.unit)
      val r: Resource[IO, Unit] = (1 to 50000).foldLeft(res) {
        case (r, _) => {
          Resource.make(r)(_ => Resource.eval(IO.unit)).flattenK
        }
      }

      assertEqv(r.use_, IO.unit)
  }

  {
    implicit def cogenForResource[F[_], A](
        implicit C: Cogen[F[(A, F[Unit])]],
        F: MonadCancel[F, Throwable]): Cogen[Resource[F, A]] =
      C.contramap(_.allocated)

    implicit val ticker = Ticker(TestContext())

    checkAll(
      "Resource[IO, *]",
      AsyncTests[Resource[IO, *]].async[Int, Int, Int](10.millis)
    ) /*(Parameters(seed =
      Some(Seed.fromBase64("0FaZxJyh_xN_NL3i_y7bNaLpaWuhO9qUPXmfxxgLIIN=").get)))*/
  }

  {
    implicit val ticker = Ticker(TestContext())

    checkAll(
      "Resource[IO, Int]",
      MonoidTests[Resource[IO, Int]].monoid
    )
  }

  {
    implicit val ticker = Ticker(TestContext())

    checkAll(
      "Resource[IO, *]",
      SemigroupKTests[Resource[IO, *]].semigroupK[Int]
    )
  }

  {
    import cats.effect.kernel.testkit.pure._
    import cats.effect.kernel.testkit.PureConcGenerators.arbitraryPureConc
    import org.scalacheck.Arbitrary

    type F[A] = PureConc[Throwable, A]

    val arbitraryPureConcResource: Arbitrary[Resource[F, Int]] =
      arbitraryResource[F, Int](implicitly, arbitraryPureConc, arbitraryPureConc, implicitly)

    checkAll(
      "Resource[PureConc, *]",
      DeferTests[Resource[F, *]].defer[Int](
        using implicitly,
        arbitraryPureConcResource,
        implicitly,
        implicitly
      )
    )
  }

  /*{
    implicit val ticker = Ticker(TestContext())

    checkAll(
      "Resource[IO, *]",
      ParallelTests[Resource[IO, *]].parallel[Int, Int]
    )
  }*/
}
