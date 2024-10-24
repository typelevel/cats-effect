/*
 * Copyright 2020-2024 Typelevel
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

import cats.{~>, SemigroupK}
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
import org.specs2.ScalaCheck
import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.util.concurrent.atomic.AtomicBoolean

class ResourceSpec extends BaseSpec with ScalaCheck with Discipline {
  // We need this for testing laws: prop runs can interfere with each other
  sequential

  "Resource[IO, *]" >> {
    "releases resources in reverse order of acquisition" in ticked { implicit ticker =>
      forAll { (as: List[(Int, Either[Throwable, Unit])]) =>
        var released: List[Int] = Nil
        val r = as.traverse {
          case (a, e) =>
            Resource.make(IO(a))(a => IO { released = a :: released } *> IO.fromEither(e))
        }
        r.use_.attempt.void must completeAs(())
        released mustEqual as.map(_._1)
      }
    }

    "makes acquires non interruptible" in ticked { implicit ticker =>
      IO.ref(false).flatMap { interrupted =>
        val fa = IO.sleep(5.seconds).onCancel(interrupted.set(true))

        Resource.make(fa)(_ => IO.unit).use_.timeout(1.second).attempt >> interrupted.get
      } must completeAs(false)
    }

    "makes acquires non interruptible, overriding uncancelable" in ticked { implicit ticker =>
      IO.ref(false).flatMap { interrupted =>
        val fa = IO.uncancelable { poll =>
          poll(IO.sleep(5.seconds)).onCancel(interrupted.set(true))
        }

        Resource.make(fa)(_ => IO.unit).use_.timeout(1.second).attempt >> interrupted.get
      } must completeAs(false)
    }

    "releases resource if interruption happens during use" in ticked { implicit ticker =>
      val flag = IO.ref(false)

      (flag, flag).tupled.flatMap {
        case (acquireFin, resourceFin) =>
          val action = IO.sleep(1.second).onCancel(acquireFin.set(true))
          val fin = resourceFin.set(true)
          val res = Resource.makeFull[IO, Unit](poll => poll(action))(_ => fin)

          res.surround(IO.sleep(5.seconds)).timeout(3.seconds).attempt >>
            (acquireFin.get, resourceFin.get).tupled
      } must completeAs(false -> true)
    }

    "supports interruptible acquires" in ticked { implicit ticker =>
      val flag = IO.ref(false)

      (flag, flag).tupled.flatMap {
        case (acquireFin, resourceFin) =>
          val action = IO.sleep(5.seconds).onCancel(acquireFin.set(true))
          val fin = resourceFin.set(true)
          val res = Resource.makeFull[IO, Unit](poll => poll(action))(_ => fin)

          res.use_.timeout(1.second).attempt >>
            (acquireFin.get, resourceFin.get).tupled
      } must completeAs(true -> false)
    }

    "supports interruptible acquires, respecting uncancelable" in ticked { implicit ticker =>
      val flag = IO.ref(false)
      val sleep = IO.sleep(1.second)
      val timeout = 500.millis

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
      } must completeAs(List(false, true, true, false))
    }

    "release is always uninterruptible" in ticked { implicit ticker =>
      val flag = IO.ref(false)
      val sleep = IO.sleep(1.second)
      val timeout = 500.millis

      flag.flatMap { releaseComplete =>
        val release = sleep >> releaseComplete.set(true)

        val resource = Resource.applyFull[IO, Unit] { poll => IO(() -> (_ => poll(release))) }

        resource.use_.timeout(timeout).attempt >> releaseComplete.get
      } must completeAs(true)
    }

    "eval" in ticked { implicit ticker =>
      forAll { (fa: IO[String]) => Resource.eval(fa).use(IO.pure) eqv fa }
    }

    "eval - interruption" in ticked { implicit ticker =>
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

      p must completeAs(1)
    }

    "eval(fa) <-> liftK.apply(fa)" in ticked { implicit ticker =>
      forAll { (fa: IO[String], f: String => IO[Int]) =>
        Resource.eval(fa).use(f) eqv Resource.liftK[IO].apply(fa).use(f)
      }
    }

    "evalMap" in ticked { implicit ticker =>
      forAll { (f: Int => IO[Int]) => Resource.eval(IO(0)).evalMap(f).use(IO.pure) eqv f(0) }
    }

    "evalMap with error fails during use" in ticked { implicit ticker =>
      case object Foo extends Exception

      Resource.eval(IO.unit).evalMap(_ => IO.raiseError[Unit](Foo)).use(IO.pure) must failAs(
        Foo)
    }

    "evalTap" in ticked { implicit ticker =>
      forAll { (f: Int => IO[Int]) =>
        Resource.eval(IO(0)).evalTap(f).use(IO.pure) eqv f(0).as(0)
      }
    }

    "evalTap with error fails during use" in ticked { implicit ticker =>
      case object Foo extends Exception

      Resource.eval(IO(0)).evalTap(_ => IO.raiseError(Foo)).void.use(IO.pure) must failAs(Foo)
    }

    "releases resources that implement AutoCloseable" in ticked { implicit ticker =>
      var closed = false
      val autoCloseable = new AutoCloseable {
        override def close(): Unit = closed = true
      }

      Resource
        .fromAutoCloseable(IO(autoCloseable))
        .surround("Hello world".pure[IO]) must completeAs("Hello world")

      closed must beTrue
    }

    "allocated releases two resources" in real {
      var a = false
      var b = false

      val test =
        Resource.make(IO.unit)(_ => IO { a = true }) >>
          Resource.make(IO.unit)(_ => IO { b = true })

      test.allocated.flatMap(_._2) >> IO {
        a must beTrue
        b must beTrue
      }
    }

    "allocated releases resources in reverse order of acquisition" in ticked {
      implicit ticker =>
        forAll { (as: List[(Int, Either[Throwable, Unit])]) =>
          var released: List[Int] = Nil
          val r = as.traverse {
            case (a, e) =>
              Resource.make(IO(a))(a => IO { released = a :: released } *> IO.fromEither(e))
          }
          r.allocated.flatMap(_._2).attempt.void must completeAs(())
          released mustEqual as.map(_._1)
        }
    }

    "allocated does not release until close is invoked" in ticked { implicit ticker =>
      val released = new java.util.concurrent.atomic.AtomicBoolean(false)
      val release = Resource.make(IO.unit)(_ => IO(released.set(true)))
      val resource = Resource.eval(IO.unit)

      // do not inline: it confuses Dotty
      val ioa = (release *> resource).allocated

      val prog = for {
        res <- ioa
        (_, close) = res
        _ <- IO(released.get() must beFalse)
        _ <- close
        _ <- IO(released.get() must beTrue)
      } yield ()

      prog must completeAs(())
    }

    "mapK" in ticked { implicit ticker =>
      forAll { (fa: Kleisli[IO, Int, Int]) =>
        val runWithTwo = new ~>[Kleisli[IO, Int, *], IO] {
          override def apply[A](fa: Kleisli[IO, Int, A]): IO[A] = fa(2)
        }
        Resource.eval(fa).mapK(runWithTwo).use(IO.pure) eqv fa(2)
      }
    }

    "attempt on Resource after mapK" in real {
      class Err extends Exception

      Resource
        .eval(IO.raiseError[Int](new Err))
        .mapK(Kleisli.liftK[IO, Int])
        .attempt
        .surround(3.pure[Kleisli[IO, Int, *]])
        .run(0)
        .mustEqual(3)
    }

    "mapK should preserve ExitCode-specific behaviour" in ticked { implicit ticker =>
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
      res.use(_ => IO.unit).attempt.void must completeAs(())
      clean.get() must beTrue

      val (clean1, res1) = sideEffectyResource
      res1.use(_ => IO.raiseError(new Throwable("oh no"))).attempt.void must completeAs(())
      clean1.get() must beFalse

      val (clean2, res2) = sideEffectyResource
      res2.mapK(Kleisli.liftK[IO, Int]).use_.run(0).attempt.void must completeAs(())
      clean2.get() must beTrue

      val (clean3, res3) = sideEffectyResource
      res3
        .mapK(Kleisli.liftK[IO, Int])
        .use(_ => Kleisli.liftF(IO.raiseError[Unit](new Throwable("oh no"))))
        .run(0)
        .attempt
        .void must completeAs(())
      clean3.get() must beFalse
    }

    "mapK respects interruptible acquires" in ticked { implicit ticker =>
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

      fa must completeAs(false -> true)
    }

    "allocated produces the same value as the resource" in ticked { implicit ticker =>
      forAll { (resource: Resource[IO, Int]) =>
        val a0 = IO.uncancelable { p =>
          p(resource.allocated).flatMap { case (b, fin) => fin.as(b) }
        }
        val a1 = resource.use(IO.pure)

        a0.flatMap(IO.pure).handleErrorWith(IO.raiseError) eqv a1
          .flatMap(IO.pure)
          .handleErrorWith(IO.raiseError)
      }
    }

    "allocated does not release until close is invoked on mapK'd Resources" in ticked {
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
          _ <- IO(released.get() must beFalse)
          _ <- close
          _ <- IO(released.get() must beTrue)
        } yield ()

        prog must completeAs(())
    }

    "use is stack-safe over binds" in ticked { implicit ticker =>
      val r = (1 to 10000)
        .foldLeft(Resource.eval(IO.unit)) {
          case (r, _) =>
            r.flatMap(_ => Resource.eval(IO.unit))
        }
        .use_
      r eqv IO.unit
    }

    "use is stack-safe over binds - 2" in real {
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

    "mapK is stack-safe over binds" in ticked { implicit ticker =>
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

      r eqv IO.unit
    }

    "attempt is stack-safe over binds" in ticked { implicit ticker =>
      val r = (1 to 10000)
        .foldLeft(Resource.eval(IO.unit)) {
          case (r, _) =>
            r.flatMap(_ => Resource.eval(IO.unit))
        }
        .attempt

      r.use_ must completeAs(())
    }

    "safe attempt suspended resource" in ticked { implicit ticker =>
      val exception = new Exception("boom!")
      val suspend = Resource.suspend[IO, Unit](IO.raiseError(exception))
      suspend.use_ must failAs(exception)
    }

    "both" >> {
      "releases resources in reverse order of acquisition" in ticked { implicit ticker =>
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

          p.use_.attempt.void must completeAs(())
          released mustEqual as.map(_._1)
        }
      }

      "parallel acquisition and release" in ticked { implicit ticker =>
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
        leftAllocated must beTrue
        rightAllocated must beTrue
        leftReleasing must beFalse
        rightReleasing must beFalse

        // after 2 seconds:
        //  both resources have started cleanup (correctness)
        ticker.ctx.advanceAndTick(1.second)
        leftReleasing must beTrue
        rightReleasing must beTrue
        leftReleased must beFalse
        rightReleased must beFalse

        // after 3 seconds:
        //  both resources have terminated cleanup (concurrency, serially it would happen after 4 seconds)
        ticker.ctx.advanceAndTick(1.second)
        leftReleased must beTrue
        rightReleased must beTrue
      }

      "safety: lhs error during rhs interruptible region" in ticked { implicit ticker =>
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
        leftAllocated must beTrue
        rightAllocated must beTrue
        leftReleasing must beFalse
        rightReleasing must beFalse

        // after 2 seconds:
        //  both resources have started cleanup (interruption, or rhs would start releasing after 3 seconds)
        ticker.ctx.advanceAndTick(1.second)
        leftReleasing must beTrue
        rightReleasing must beTrue
        leftReleased must beFalse
        rightReleased must beFalse

        // after 3 seconds:
        //  both resources have terminated cleanup (concurrency, serially it would happen after 4 seconds)
        ticker.ctx.advanceAndTick(1.second)
        leftReleased must beTrue
        rightReleased must beTrue
      }

      "safety: rhs error during lhs uninterruptible region" in ticked { implicit ticker =>
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
        leftAllocated must beFalse
        rightAllocated must beTrue
        rightErrored must beFalse
        leftReleasing must beFalse
        rightReleasing must beFalse

        // after 2 seconds:
        //  rhs has failed, release blocked since lhs is in uninterruptible allocation
        ticker.ctx.advanceAndTick(1.second)
        leftAllocated must beFalse
        rightAllocated must beTrue
        rightErrored must beTrue
        leftReleasing must beFalse
        rightReleasing must beFalse

        // after 3 seconds:
        //  lhs completes allocation (concurrency, serially it would happen after 4 seconds)
        //  both resources have started cleanup (correctness, error propagates to both sides)
        ticker.ctx.advanceAndTick(1.second)
        leftAllocated must beTrue
        leftReleasing must beTrue
        rightReleasing must beTrue
        leftReleased must beFalse
        rightReleased must beFalse

        // after 4 seconds:
        //  both resource have terminated cleanup (concurrency, serially it would happen after 5 seconds)
        ticker.ctx.advanceAndTick(1.second)
        leftReleased must beTrue
        rightReleased must beTrue
      }

      "propagate the exit case" in {
        import Resource.ExitCase

        "use successfully, test left" >> ticked { implicit ticker =>
          var got: ExitCase = null
          val r = Resource.onFinalizeCase(ec => IO { got = ec })
          r.both(Resource.unit).use(_ => IO.unit) must completeAs(())
          got mustEqual ExitCase.Succeeded
        }

        "use successfully, test right" >> ticked { implicit ticker =>
          var got: ExitCase = null
          val r = Resource.onFinalizeCase(ec => IO { got = ec })
          Resource.unit.both(r).use(_ => IO.unit) must completeAs(())
          got mustEqual ExitCase.Succeeded
        }

        "use errored, test left" >> ticked { implicit ticker =>
          var got: ExitCase = null
          val ex = new Exception
          val r = Resource.onFinalizeCase(ec => IO { got = ec })
          r.both(Resource.unit).use(_ => IO.raiseError(ex)) must failAs(ex)
          got mustEqual ExitCase.Errored(ex)
        }

        "use errored, test right" >> ticked { implicit ticker =>
          var got: ExitCase = null
          val ex = new Exception
          val r = Resource.onFinalizeCase(ec => IO { got = ec })
          Resource.unit.both(r).use(_ => IO.raiseError(ex)) must failAs(ex)
          got mustEqual ExitCase.Errored(ex)
        }

        "right errored, test left" >> ticked { implicit ticker =>
          var got: ExitCase = null
          val ex = new Exception
          val r = Resource.onFinalizeCase(ec => IO { got = ec })
          r.both(Resource.eval(IO.sleep(1.second) *> IO.raiseError(ex))).use_ must failAs(ex)
          got mustEqual ExitCase.Errored(ex)
        }

        "left errored, test right" >> ticked { implicit ticker =>
          var got: ExitCase = null
          val ex = new Exception
          val r = Resource.onFinalizeCase(ec => IO { got = ec })
          Resource.eval(IO.sleep(1.second) *> IO.raiseError(ex)).both(r).use_ must failAs(ex)
          got mustEqual ExitCase.Errored(ex)
        }

        "use canceled, test left" >> ticked { implicit ticker =>
          var got: ExitCase = null
          val r = Resource.onFinalizeCase(ec => IO { got = ec })
          r.both(Resource.unit).use(_ => IO.canceled) must selfCancel
          got mustEqual ExitCase.Canceled
        }

        "use canceled, test right" >> ticked { implicit ticker =>
          var got: ExitCase = null
          val r = Resource.onFinalizeCase(ec => IO { got = ec })
          Resource.unit.both(r).use(_ => IO.canceled) must selfCancel
          got mustEqual ExitCase.Canceled
        }

        "right canceled, test left" >> ticked { implicit ticker =>
          var got: ExitCase = null
          val r = Resource.onFinalizeCase(ec => IO { got = ec })
          r.both(Resource.eval(IO.sleep(1.second) *> IO.canceled)).use_ must selfCancel
          got mustEqual ExitCase.Canceled
        }

        "left canceled, test right" >> ticked { implicit ticker =>
          var got: ExitCase = null
          val r = Resource.onFinalizeCase(ec => IO { got = ec })
          Resource.eval(IO.sleep(1.second) *> IO.canceled).both(r).use_ must selfCancel
          got mustEqual ExitCase.Canceled
        }
      }
    }

    "releases both resources on combineK" in ticked { implicit ticker =>
      var acquired: Set[Int] = Set.empty
      var released: Set[Int] = Set.empty
      def observe(a: Int) =
        Resource.make(IO(acquired += a).as(a))(a => IO(released += a))

      observe(1).combineK(observe(2)).use_.attempt.void must completeAs(())
      released mustEqual acquired
    }

    "releases both resources on combineK when using a SemigroupK instance that discards allocated values" in ticked {
      implicit ticker =>
        implicit val sgk: SemigroupK[IO] = new SemigroupK[IO] {
          override def combineK[A](x: IO[A], y: IO[A]): IO[A] = x <* y
        }
        var acquired: Set[Int] = Set.empty
        var released: Set[Int] = Set.empty
        def observe(a: Int) =
          Resource.make(IO(acquired += a) *> IO.pure(a))(a => IO(released += a))

        observe(1).combineK(observe(2)).use_.attempt.void must completeAs(())
        released mustEqual acquired
    }

    "combineK" should {
      "behave like orElse when underlying effect does" in ticked { implicit ticker =>
        prop { (r1: Resource[IO, Int], r2: Resource[IO, Int]) =>
          val lhs = r1.orElse(r2)
          val rhs = r1 <+> r2

          lhs eqv rhs
        }
      }

      "behave like underlying effect" in ticked { implicit ticker =>
        forAll { (ot1: OptionT[IO, Int], ot2: OptionT[IO, Int]) =>
          val lhs = Resource.eval(ot1 <+> ot2).use(OptionT.pure[IO](_)).value
          val rhs = (Resource.eval(ot1) <+> Resource.eval(ot2)).use(OptionT.pure[IO](_)).value

          lhs eqv rhs
        }
      }

      "propagate the exit case" in {
        import Resource.ExitCase

        "use successfully, test left" >> ticked { implicit ticker =>
          var got: ExitCase = null
          val r = Resource.onFinalizeCase(ec => IO { got = ec })
          r.combineK(Resource.unit).use(_ => IO.unit) must completeAs(())
          got mustEqual ExitCase.Succeeded
        }

        "use errored, test left" >> ticked { implicit ticker =>
          var got: ExitCase = null
          val ex = new Exception
          val r = Resource.onFinalizeCase(ec => IO { got = ec })
          r.combineK(Resource.unit).use(_ => IO.raiseError(ex)) must failAs(ex)
          got mustEqual ExitCase.Errored(ex)
        }

        "left errored, test left" >> ticked { implicit ticker =>
          var got: ExitCase = null
          val ex = new Exception
          val r = Resource.onFinalizeCase(ec => IO { got = ec }) *>
            Resource.eval(IO.raiseError(ex))
          r.combineK(Resource.unit).use_ must completeAs(())
          got mustEqual ExitCase.Succeeded
        }

        "left errored, test right" >> ticked { implicit ticker =>
          var got: ExitCase = null
          val ex = new Exception
          val r = Resource.onFinalizeCase(ec => IO { got = ec })
          Resource.eval(IO.raiseError(ex)).combineK(r).use_ must completeAs(())
          got mustEqual ExitCase.Succeeded
        }

        "left errored, use errored, test right" >> ticked { implicit ticker =>
          var got: ExitCase = null
          val ex = new Exception
          val r = Resource.onFinalizeCase(ec => IO { got = ec })
          Resource
            .eval(IO.raiseError(new Exception))
            .combineK(r)
            .use(_ => IO.raiseError(ex)) must failAs(ex)
          got mustEqual ExitCase.Errored(ex)
        }

        "use canceled, test left" >> ticked { implicit ticker =>
          var got: ExitCase = null
          val r = Resource.onFinalizeCase(ec => IO { got = ec })
          r.combineK(Resource.unit).use(_ => IO.canceled) must selfCancel
          got mustEqual ExitCase.Canceled
        }

        "left errored, use canceled, test right" >> ticked { implicit ticker =>
          var got: ExitCase = null
          val r = Resource.onFinalizeCase(ec => IO { got = ec })
          Resource
            .eval(IO.raiseError(new Exception))
            .combineK(r)
            .use(_ => IO.canceled) must selfCancel
          got mustEqual ExitCase.Canceled
        }
      }
    }

    "surround" should {
      "wrap an effect in a usage and ignore the value produced by resource" in ticked {
        implicit ticker =>
          val r = Resource.eval(IO.pure(0))
          val surroundee = IO("hello")
          val surrounded = r.surround(surroundee)

          surrounded eqv surroundee
      }
    }

    "surroundK" should {
      "wrap an effect in a usage, ignore the value produced by resource and return FunctionK" in ticked {
        implicit ticker =>
          val r = Resource.eval(IO.pure(0))
          val surroundee = IO("hello")
          val surround = r.surroundK
          val surrounded = surround(surroundee)

          surrounded eqv surroundee
      }
    }

    "evalOn" should {
      "run acquire and release on provided ExecutionContext" in ticked { implicit ticker =>
        forAll { (executionContext: ExecutionContext) =>
          val assertion =
            IO.executionContext.flatMap(ec => IO(ec mustEqual executionContext)).void
          Resource.make(assertion)(_ => assertion).evalOn(executionContext).use_.as(true)
        }
      }
    }
  }

  "Async[Resource]" >> {
    val wait = IO.sleep(1.second)
    val waitR = Resource.eval(wait)

    "onCancel" should {
      "not fire when adjacent to uncancelable" in ticked { implicit ticker =>
        var fired = false

        val test =
          Resource.eval(IO.canceled).uncancelable.onCancel(Resource.eval(IO { fired = true }))

        test.use_.unsafeToFuture()
        ticker.ctx.tick()

        fired must beFalse
      }
    }

    "async" should {
      "forward inner finalizers into outer scope" in ticked { implicit ticker =>
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
        innerClosed must beFalse
        outerClosed must beFalse

        ticker.ctx.advanceAndTick(1.second)
        innerClosed must beTrue
        outerClosed must beTrue
      }
    }

    "forceR" >> {
      "closes left scope" in ticked { implicit ticker =>
        var leftClosed = false
        var rightClosed = false

        val target =
          Resource.make(wait)(_ => IO { leftClosed = true }) !>
            Resource.make(wait)(_ => IO { rightClosed = true })

        target.use_.unsafeToFuture()

        ticker.ctx.tick()
        ticker.ctx.advanceAndTick(1.second)
        leftClosed must beTrue
        rightClosed must beFalse

        ticker.ctx.advanceAndTick(1.second)
        rightClosed must beTrue
      }
    }

    "onCancel" >> {
      "catches inner cancelation" in ticked { implicit ticker =>
        var innerClosed = false
        var outerClosed = false
        var canceled = false

        val inner =
          Resource.make(IO.unit)(_ => IO { innerClosed = true }) *> Resource.eval(IO.canceled)
        val outer = Resource.make(IO.unit)(_ => IO { outerClosed = true })

        val target = inner.onCancel(Resource.eval(IO { canceled = true })) *> outer

        target.use_ must selfCancel
        innerClosed must beTrue
        outerClosed must beFalse
        canceled must beTrue
      }

      "does not extend across the region" in ticked { implicit ticker =>
        var innerClosed = false
        var outerClosed = false
        var canceled = false

        val inner = Resource.make(IO.unit)(_ => IO { innerClosed = true })
        val outer =
          Resource.make(IO.unit)(_ => IO { outerClosed = true }) *> Resource.eval(IO.canceled)

        val target = inner.onCancel(Resource.eval(IO { canceled = true })) *> outer

        target.use_ must selfCancel
        innerClosed must beTrue
        outerClosed must beTrue
        canceled must beFalse // if this is true, it means the scope was extended rather than being closed!
      }
    }

    "race" >> {
      "two acquisitions, closing the loser and maintaining the winner" in ticked {
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
          winnerClosed must beFalse
          loserClosed must beFalse
          completed must beFalse
          results must beNull

          ticker.ctx.advanceAndTick(50.millis)
          winnerClosed must beFalse
          loserClosed must beTrue
          completed must beFalse
          results must beLeft("winner")

          ticker.ctx.advanceAndTick(50.millis)
          winnerClosed must beFalse
          loserClosed must beTrue
          completed must beFalse
          results must beLeft("winner")

          ticker.ctx.advanceAndTick(1.second)
          winnerClosed must beTrue
          completed must beTrue
      }

      "closes the loser eagerly" in real {
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
              acquiredRight.get.ifM(loserReleased.get.map(_ must beRight[Unit]), IO.pure(ok))
            case Right(()) =>
              acquiredLeft.get.ifM(loserReleased.get.map(_ must beLeft[Unit]), IO.pure(ok))
          }
        }

        TestControl.executeEmbed(go.replicateA_(100)).as(true)
      }
    }

    "start" >> {
      "runs fibers in parallel" in ticked { implicit ticker =>
        var completed = false
        val target = waitR.start *> waitR *> Resource.eval(IO { completed = true })

        target.use_.unsafeToFuture()
        ticker.ctx.tickAll()
        completed must beTrue
      }

      "run finalizers when completed and outer scope is closed" in ticked { implicit ticker =>
        var i = 0
        var completed = false
        val target = Resource.make(IO { completed = true })(_ => IO(i += 1)).start *> waitR

        target.use_ must completeAs(())
        completed must beTrue
        i mustEqual 1
      }

      "run finalizers after completion when outer scope is closed" in ticked {
        implicit ticker =>
          var i = 0
          var completed = false

          val finish = Resource.eval(IO { completed = true })
          val target = Resource.make(wait)(_ => IO(i += 1)).start *> finish

          target.use_.unsafeToFuture()
          ticker.ctx.advanceAndTick(50.millis)

          completed must beTrue
          i mustEqual 0

          ticker.ctx.advanceAndTick(1.second)
          i mustEqual 1
      }

      "run finalizers once when canceled" in ticked { implicit ticker =>
        var i = 0

        val fork = Resource.make(IO.unit)(_ => IO(i += 1)) *> waitR *> waitR
        val target = fork.start flatMap { f => waitR *> f.cancel }

        target.use_.unsafeToFuture()

        ticker.ctx.tick()
        ticker.ctx.advanceAndTick(1.second)
        i mustEqual 1

        ticker.ctx.advanceAndTick(1.second)
        i mustEqual 1
      }

      "join scope contained by outer" in ticked { implicit ticker =>
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
        i mustEqual 0

        ticker.ctx.advanceAndTick(900.millis)
        i mustEqual 0
        completed must beFalse

        ticker.ctx.advanceAndTick(1.second)
        i mustEqual 1
        completed must beTrue
      }
    }
  }

  "Concurrent[Resource]" >> {
    "both" >> {
      "parallel acquisition and release" in ticked { implicit ticker =>
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
        leftAllocated must beTrue
        rightAllocated must beTrue
        leftReleasing must beFalse
        rightReleasing must beFalse

        // after 2 seconds:
        //  both resources have started cleanup (correctness)
        ticker.ctx.advanceAndTick(1.second)
        leftReleasing must beTrue
        rightReleasing must beTrue
        leftReleased must beFalse
        rightReleased must beFalse

        // after 3 seconds:
        //  both resources have terminated cleanup (concurrency, serially it would happen after 4 seconds)
        ticker.ctx.advanceAndTick(1.second)
        leftReleased must beTrue
        rightReleased must beTrue
      }
    }

    "memoize" >> {
      "memoize and then flatten is identity" in ticked { implicit ticker =>
        forAll { (r: Resource[IO, Int]) => r.memoize.flatten eqv r }
      }
      "allocates once and releases at end" in ticked { implicit ticker =>
        (IO.ref(0), IO.ref(0))
          .mapN { (acquired, released) =>
            val r = Resource.make(acquired.update(_ + 1).void)(_ => released.update(_ + 1))
            def acquiredMustBe(i: Int) = acquired.get.map(_ must be_==(i)).void
            def releasedMustBe(i: Int) = released.get.map(_ must be_==(i)).void
            r.memoize.use { memo =>
              acquiredMustBe(0) *> releasedMustBe(0) *>
                memo.surround(acquiredMustBe(1) *> releasedMustBe(0)) *>
                acquiredMustBe(1) *> releasedMustBe(0) *>
                memo.surround(acquiredMustBe(1) *> releasedMustBe(0)) *>
                acquiredMustBe(1) *> releasedMustBe(0)
            } *> acquiredMustBe(1) *> releasedMustBe(1)
          }
          .flatten
          .void must completeAs(())
      }
      "does not allocate if not used" in ticked { implicit ticker =>
        (IO.ref(0), IO.ref(0))
          .mapN { (acquired, released) =>
            val r = Resource.make(acquired.update(_ + 1).void)(_ => released.update(_ + 1))
            def acquiredMustBe(i: Int) = acquired.get.map(_ must be_==(i)).void
            def releasedMustBe(i: Int) = released.get.map(_ must be_==(i)).void
            r.memoize.surround(acquiredMustBe(0) *> releasedMustBe(0)) *>
              acquiredMustBe(0) *> releasedMustBe(0)
          }
          .flatten
          .void must completeAs(())
      }
      "does not leak if canceled" in ticked { implicit ticker =>
        (IO.ref(0), IO.ref(0)).flatMapN { (acquired, released) =>
          val r = Resource.make(IO.sleep(2.seconds) *> acquired.update(_ + 1).void) { _ =>
            released.update(_ + 1)
          }

          def acquiredMustBe(i: Int) = acquired.get.map(_ must be_==(i)).void
          def releasedMustBe(i: Int) = released.get.map(_ must be_==(i)).void

          r.memoize.use { memo =>
            memo.timeoutTo(1.second, Resource.unit[IO]).use_
          } *> acquiredMustBe(1) *> releasedMustBe(1)
        }.void must completeAs(())
      }
    }
  }

  "attempt" >> {

    "releases resource on error" in ticked { implicit ticker =>
      IO.ref(0)
        .flatMap { ref =>
          val resource = Resource.make(ref.update(_ + 1))(_ => ref.update(_ + 1))
          val error = Resource.raiseError[IO, Unit, Throwable](new Exception)
          (resource *> error).attempt.use { r =>
            IO(r must beLeft) *> ref.get.map { _ must be_==(2) }
          }
        }
        .void must completeAs(())
    }

    "acquire is interruptible" in ticked { implicit ticker =>
      val sleep = IO.never
      val timeout = 500.millis
      IO.ref(false).flatMap { ref =>
        val r = Resource.makeFull[IO, Unit] { poll => poll(sleep).onCancel(ref.set(true)) }(_ =>
          IO.unit)
        r.attempt.timeout(timeout).attempt.use_ *> ref.get
      } must completeAs(true)
    }
  }

  "uncancelable" >> {
    "does not suppress errors within use" in real {
      case object TestException extends RuntimeException

      for {
        slot <- IO.deferred[Resource.ExitCase]
        rsrc = Resource.makeCase(IO.unit)((_, ec) => slot.complete(ec).void)
        _ <- rsrc.uncancelable.use(_ => IO.raiseError(TestException)).handleError(_ => ())
        results <- slot.get

        _ <- IO {
          results mustEqual Resource.ExitCase.Errored(TestException)
        }
      } yield ok
    }

    "use is stack-safe over binds" in ticked { implicit ticker =>
      val res = Resource.make(IO.unit)(_ => IO.unit)
      val r = (1 to 50000)
        .foldLeft(res) {
          case (r, _) =>
            r.flatMap(_ => res)
        }
        .uncancelable
        .use_
      r eqv IO.unit
    }

  }

  "allocatedCase" >> {
    "is stack-safe over binds" in ticked { implicit ticker =>
      val res = Resource.make(IO.unit)(_ => IO.unit)
      val r = (1 to 50000)
        .foldLeft(res) {
          case (r, _) =>
            r.flatMap(_ => res)
        }
        .allocatedCase
        .map(_._1)
      r eqv IO.unit

    }
  }

  "Resource[Resource[IO, *], *]" should {
    "flatten with finalizers inside-out" in ticked { implicit ticker =>
      var results = ""

      val r: Resource[Resource[IO, *], Int] =
        Resource.make(Resource.make(IO.pure(42))(_ => IO(results += "a")))(_ =>
          Resource.eval(IO(results += "b")))

      r.flattenK.use(IO.pure(_)) must completeAs(42)
      results mustEqual "ab"
    }

    "flattenK is stack-safe over binds" in ticked { implicit ticker =>
      val res = Resource.make(IO.unit)(_ => IO.unit)
      val r: Resource[IO, Unit] = (1 to 50000).foldLeft(res) {
        case (r, _) => {
          Resource.make(r)(_ => Resource.eval(IO.unit)).flattenK
        }
      }

      r.use_ eqv IO.unit
    }

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
        implicitly,
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
