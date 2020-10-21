/*
 * Copyright 2020 Typelevel
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
import cats.data.Kleisli
import cats.effect.testkit.TestContext
import cats.kernel.laws.discipline.MonoidTests
import cats.laws.discipline._
import cats.laws.discipline.arbitrary._
import cats.syntax.all._

import org.scalacheck.Prop, Prop.forAll
import org.specs2.ScalaCheck
import org.typelevel.discipline.specs2.mutable.Discipline

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
        r.use(IO.pure).attempt.void must completeAs(())
        released mustEqual as.map(_._1)
      }
    }

    "releases resources that implement AutoCloseable" in ticked { implicit ticker =>
      var closed = false
      val autoCloseable = new AutoCloseable {
        override def close(): Unit = closed = true
      }

      Resource
        .fromAutoCloseable(IO(autoCloseable))
        .use(_ => IO.pure("Hello world")) must completeAs("Hello world")

      closed must beTrue
    }

    "liftF" in ticked { implicit ticker =>
      forAll { (fa: IO[String]) => Resource.liftF(fa).use(IO.pure) eqv fa }
    }

    "liftF - interruption" in ticked { implicit ticker =>
      def resource(d: Deferred[IO, Int]): Resource[IO, Unit] =
        for {
          _ <- Resource.make(IO.unit)(_ => d.complete(1).void)
          _ <- Resource.liftF(IO.never[Unit])
        } yield ()

      def p =
        for {
          d <- Deferred[IO, Int]
          r = resource(d).use(IO.pure)
          fiber <- r.start
          _ <- IO.sleep(200.millis)
          _ <- fiber.cancel
          res <- d.get
        } yield res

      p must completeAs(1)
    }

    "liftF(fa) <-> liftK.apply(fa)" in ticked { implicit ticker =>
      forAll { (fa: IO[String], f: String => IO[Int]) =>
        Resource.liftF(fa).use(f) eqv Resource.liftK[IO].apply(fa).use(f)
      }
    }

    "evalMap" in ticked { implicit ticker =>
      forAll { (f: Int => IO[Int]) => Resource.liftF(IO(0)).evalMap(f).use(IO.pure) eqv f(0) }
    }

    "evalMap with error fails during use" in ticked { implicit ticker =>
      case object Foo extends Exception

      Resource.liftF(IO.unit).evalMap(_ => IO.raiseError[Unit](Foo)).use(IO.pure) must failAs(
        Foo)
    }

    "evalTap" in ticked { implicit ticker =>
      forAll { (f: Int => IO[Int]) =>
        Resource.liftF(IO(0)).evalTap(f).use(IO.pure) eqv f(0).as(0)
      }
    }

    "evalTap with error fails during use" in ticked { implicit ticker =>
      case object Foo extends Exception

      Resource.liftF(IO(0)).evalTap(_ => IO.raiseError(Foo)).void.use(IO.pure) must failAs(Foo)
    }

    "mapK" in ticked { implicit ticker =>
      forAll { (fa: Kleisli[IO, Int, Int]) =>
        val runWithTwo = new ~>[Kleisli[IO, Int, *], IO] {
          override def apply[A](fa: Kleisli[IO, Int, A]): IO[A] = fa(2)
        }
        Resource.liftF(fa).mapK(runWithTwo).use(IO.pure) eqv fa(2)
      }
    }

    "mapK should preserve ExitCode-specific behaviour" in ticked { implicit ticker =>
      val takeAnInteger = new ~>[IO, Kleisli[IO, Int, *]] {
        override def apply[A](fa: IO[A]): Kleisli[IO, Int, A] = Kleisli.liftF(fa)
      }

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
      res2
        .mapK(takeAnInteger)
        .use(_ => ().pure[Kleisli[IO, Int, *]])
        .run(0)
        .attempt
        .void must completeAs(())
      clean2.get() must beTrue

      val (clean3, res3) = sideEffectyResource
      res3
        .mapK(takeAnInteger)
        .use(_ => Kleisli.liftF(IO.raiseError[Unit](new Throwable("oh no"))))
        .run(0)
        .attempt
        .void must completeAs(())
      clean3.get() must beFalse
    }

    "allocated produces the same value as the resource" in ticked { implicit ticker =>
      forAll { (resource: Resource[IO, Int]) =>
        val a0 = Resource(resource.allocated).use(IO.pure).attempt
        val a1 = resource.use(IO.pure).attempt

        a0 eqv a1
      }
    }

    "use is stack-safe over binds" in ticked { implicit ticker =>
      val r = (1 to 10000)
        .foldLeft(Resource.liftF(IO.unit)) {
          case (r, _) =>
            r.flatMap(_ => Resource.liftF(IO.unit))
        }
        .use(IO.pure)
      r eqv IO.unit
    }

    "mapK is stack-safe over binds" in ticked { implicit ticker =>
      val r = (1 to 10000)
        .foldLeft(Resource.liftF(IO.unit)) {
          case (r, _) =>
            r.flatMap(_ => Resource.liftF(IO.unit))
        }
        .mapK(new ~>[IO, IO] {
          def apply[A](a: IO[A]): IO[A] = a
        })
        .use(IO.pure)
      r eqv IO.unit
    }

    "allocate does not release until close is invoked" in ticked { implicit ticker =>
      val released = new java.util.concurrent.atomic.AtomicBoolean(false)
      val release = Resource.make(IO.unit)(_ => IO(released.set(true)))
      val resource = Resource.liftF(IO.unit)

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

    "allocate does not release until close is invoked on mapK'd Resources" in ticked {
      implicit ticker =>
        val released = new java.util.concurrent.atomic.AtomicBoolean(false)

        val runWithTwo = new ~>[Kleisli[IO, Int, *], IO] {
          override def apply[A](fa: Kleisli[IO, Int, A]): IO[A] = fa(2)
        }
        val takeAnInteger = new ~>[IO, Kleisli[IO, Int, *]] {
          override def apply[A](fa: IO[A]): Kleisli[IO, Int, A] = Kleisli.liftF(fa)
        }
        val plusOne = Kleisli { (i: Int) => IO(i + 1) }
        val plusOneResource = Resource.liftF(plusOne)

        val release = Resource.make(IO.unit)(_ => IO(released.set(true)))
        val resource = Resource.liftF(IO.unit)

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

    "safe attempt suspended resource" in ticked { implicit ticker =>
      val exception = new Exception("boom!")
      val suspend = Resource.suspend[IO, Unit](IO.raiseError(exception))
      suspend.use(IO.pure) must failAs(exception)
    }

    "parZip - releases resources in reverse order of acquisition" in ticked { implicit ticker =>
      // conceptually asserts that:
      //   forAll (r: Resource[F, A]) then r <-> r.parZip(Resource.unit) <-> Resource.unit.parZip(r)
      // needs to be tested manually to assert the equivalence during cleanup as well
      forAll { (as: List[(Int, Either[Throwable, Unit])], rhs: Boolean) =>
        var released: List[Int] = Nil
        val r = as.traverse {
          case (a, e) =>
            Resource.make(IO(a))(a => IO { released = a :: released } *> IO.fromEither(e))
        }
        val unit = ().pure[Resource[IO, *]]
        val p = if (rhs) r.parZip(unit) else unit.parZip(r)

        p.use(IO.pure).attempt.void must completeAs(())
        released mustEqual as.map(_._1)
      }
    }

    "parZip - parallel acquisition and release" in ticked { implicit ticker =>
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

      (lhs, rhs).parTupled.use(_ => wait).unsafeToFuture()

      // after 1 second:
      //  both resources have allocated (concurrency, serially it would happen after 2 seconds)
      //  resources are still open during `use` (correctness)
      ticker.ctx.tick(1.second)
      leftAllocated must beTrue
      rightAllocated must beTrue
      leftReleasing must beFalse
      rightReleasing must beFalse

      // after 2 seconds:
      //  both resources have started cleanup (correctness)
      ticker.ctx.tick(1.second)
      leftReleasing must beTrue
      rightReleasing must beTrue
      leftReleased must beFalse
      rightReleased must beFalse

      // after 3 seconds:
      //  both resources have terminated cleanup (concurrency, serially it would happen after 4 seconds)
      ticker.ctx.tick(1.second)
      leftReleased must beTrue
      rightReleased must beTrue
    }

    "parZip - safety: lhs error during rhs interruptible region" in ticked { implicit ticker =>
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
        _ <- Resource.liftF(wait(1) >> IO.raiseError[Unit](new Exception))
      } yield ()

      val rhs = for {
        _ <- Resource.make(wait(1) >> IO { rightAllocated = true }) { _ =>
          IO { rightReleasing = true } >> wait(1) >> IO { rightReleased = true }
        }
        _ <- Resource.liftF(wait(2))
      } yield ()

      (lhs, rhs).parTupled.use(_ => IO.unit).handleError(_ => ()).unsafeToFuture()

      // after 1 second:
      //  both resources have allocated (concurrency, serially it would happen after 2 seconds)
      //  resources are still open during `flatMap` (correctness)
      ticker.ctx.tick(1.second)
      leftAllocated must beTrue
      rightAllocated must beTrue
      leftReleasing must beFalse
      rightReleasing must beFalse

      // after 2 seconds:
      //  both resources have started cleanup (interruption, or rhs would start releasing after 3 seconds)
      ticker.ctx.tick(1.second)
      leftReleasing must beTrue
      rightReleasing must beTrue
      leftReleased must beFalse
      rightReleased must beFalse

      // after 3 seconds:
      //  both resources have terminated cleanup (concurrency, serially it would happen after 4 seconds)
      ticker.ctx.tick(1.second)
      leftReleased must beTrue
      rightReleased must beTrue
    }

    "parZip - safety: rhs error during lhs uninterruptible region" in ticked {
      implicit ticker =>
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

        (lhs, rhs).parTupled.use(_ => wait(1)).handleError(_ => ()).unsafeToFuture()

        // after 1 second:
        //  rhs has partially allocated, lhs executing
        ticker.ctx.tick(1.second)
        leftAllocated must beFalse
        rightAllocated must beTrue
        rightErrored must beFalse
        leftReleasing must beFalse
        rightReleasing must beFalse

        // after 2 seconds:
        //  rhs has failed, release blocked since lhs is in uninterruptible allocation
        ticker.ctx.tick(1.second)
        leftAllocated must beFalse
        rightAllocated must beTrue
        rightErrored must beTrue
        leftReleasing must beFalse
        rightReleasing must beFalse

        // after 3 seconds:
        //  lhs completes allocation (concurrency, serially it would happen after 4 seconds)
        //  both resources have started cleanup (correctness, error propagates to both sides)
        ticker.ctx.tick(1.second)
        leftAllocated must beTrue
        leftReleasing must beTrue
        rightReleasing must beTrue
        leftReleased must beFalse
        rightReleased must beFalse

        // after 4 seconds:
        //  both resource have terminated cleanup (concurrency, serially it would happen after 5 seconds)
        ticker.ctx.tick(1.second)
        leftReleased must beTrue
        rightReleased must beTrue
    }

    "releases both resources on combineK" in ticked { implicit ticker =>
      var acquired: Set[Int] = Set.empty
      var released: Set[Int] = Set.empty
      def observe(a: Int) =
        Resource.make(IO(acquired += a).as(a))(a => IO(released += a))

      observe(1).combineK(observe(2)).use(_ => IO.unit).attempt.void must completeAs(())
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

        observe(1).combineK(observe(2)).use(_ => IO.unit).attempt.void must completeAs(())
        released mustEqual acquired
    }

    "combineK - should behave like orElse when underlying effect does" in ticked {
      implicit ticker =>
        forAll { (r1: Resource[IO, Int], r2: Resource[IO, Int]) =>
          val lhs = r1.orElse(r2).use(IO.pure)
          val rhs = (r1 <+> r2).use(IO.pure)

          lhs eqv rhs
        }
    }

    // "combinek - should behave like underlying effect" in ticked { implicit ticker =>
    //   forAll { (ot1: OptionT[IO, Int], ot2: OptionT[IO, Int]) =>
    //     val lhs = Resource.liftF(ot1 <+> ot2).use(OptionT.pure[IO](_)).value
    //     val rhs = (Resource.liftF(ot1) <+> Resource.liftF(ot2)).use(OptionT.pure[IO](_)).value

    //     lhs eqv rhs
    //   }
    // }

  }

  {
    implicit val ticker = Ticker(TestContext())

    checkAll(
      "Resource[IO, *]",
      MonadErrorTests[Resource[IO, *], Throwable].monadError[Int, Int, Int]
    )
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
    implicit val ticker = Ticker(TestContext())

    // do NOT inline this val; it causes the 2.13.0 compiler to crash for... reasons (see: scala/bug#11732)
    val module: ParallelTests.Aux[Resource[IO, *], Resource.Par[IO, *]] =
      ParallelTests[Resource[IO, *]]

    checkAll(
      "Resource[IO, *]",
      module.parallel[Int, Int]
    )
  }
}
