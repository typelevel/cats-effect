/*
 * Copyright 2020-2022 Typelevel
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

import cats.data.{ OptionT, EitherT, IorT, Ior, WriterT }
import cats.syntax.all._
import org.scalacheck.Prop, Prop.forAll
import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.duration._
import scala.util.Success

class MemoizeSpec extends BaseSpec with Discipline {

  sequential

  "Concurrent.memoize" >> {

    "Concurrent.memoize does not evaluate the effect if the inner `F[A]` isn't bound" in ticked {
      implicit ticker =>
        val op = for {
          ref <- Ref.of[IO, Int](0)
          action = ref.update(_ + 1)
          _ <- Concurrent[IO].memoize(action)
          v <- ref.get
        } yield v

        val result = op.unsafeToFuture()
        ticker.ctx.tick()

        result.value mustEqual Some(Success(0))
    }

    "Concurrent.memoize evaluates effect once if inner `F[A]` is bound twice" in ticked {
      implicit ticker =>
        val op = for {
          ref <- Ref.of[IO, Int](0)
          action = ref.modify { s =>
            val ns = s + 1
            ns -> ns
          }
          memoized <- Concurrent[IO].memoize(action)
          x <- memoized
          y <- memoized
          v <- ref.get
        } yield (x, y, v)

        val result = op.unsafeToFuture()
        ticker.ctx.tick()

        result.value mustEqual Some(Success((1, 1, 1)))
    }

    "Concurrent.memoize effect evaluates effect once if the inner `F[A]` is bound twice (race)" in ticked {
      implicit ticker =>
        val op = for {
          ref <- Ref.of[IO, Int](0)
          action = ref.modify { s =>
            val ns = s + 1
            ns -> ns
          }
          memoized <- Concurrent[IO].memoize(action)
          _ <- memoized.start
          x <- memoized
          _ <- IO(ticker.ctx.tick())
          v <- ref.get
        } yield x -> v

        val result = op.unsafeToFuture()
        ticker.ctx.tick()

        result.value mustEqual Some(Success((1, 1)))
    }

    "Concurrent.memoize and then flatten is identity" in ticked { implicit ticker =>
      forAll { (fa: IO[Int]) => Concurrent[IO].memoize(fa).flatten eqv fa }
    }

    "Memoized effects can be canceled when there are no other active subscribers (1)" in ticked {
      implicit ticker =>
        val op = for {
          completed <- Ref[IO].of(false)
          action = IO.sleep(200.millis) >> completed.set(true)
          memoized <- Concurrent[IO].memoize(action)
          fiber <- memoized.start
          _ <- IO.sleep(100.millis)
          _ <- fiber.cancel
          _ <- IO.sleep(300.millis)
          res <- completed.get
        } yield res

        val result = op.unsafeToFuture()
        ticker.ctx.tickAll()

        result.value mustEqual Some(Success(false))
    }

    "Memoized effects can be canceled when there are no other active subscribers (2)" in ticked {
      implicit ticker =>
        val op = for {
          completed <- Ref[IO].of(false)
          action = IO.sleep(300.millis) >> completed.set(true)
          memoized <- Concurrent[IO].memoize(action)
          fiber1 <- memoized.start
          _ <- IO.sleep(100.millis)
          fiber2 <- memoized.start
          _ <- IO.sleep(100.millis)
          _ <- fiber2.cancel
          _ <- fiber1.cancel
          _ <- IO.sleep(400.millis)
          res <- completed.get
        } yield res

        val result = op.unsafeToFuture()
        ticker.ctx.tickAll()

        result.value mustEqual Some(Success(false))
    }

    "Memoized effects can be canceled when there are no other active subscribers (3)" in ticked {
      implicit ticker =>
        val op = for {
          completed <- Ref[IO].of(false)
          action = IO.sleep(300.millis) >> completed.set(true)
          memoized <- Concurrent[IO].memoize(action)
          fiber1 <- memoized.start
          _ <- IO.sleep(100.millis)
          fiber2 <- memoized.start
          _ <- IO.sleep(100.millis)
          _ <- fiber1.cancel
          _ <- fiber2.cancel
          _ <- IO.sleep(400.millis)
          res <- completed.get
        } yield res

        val result = op.unsafeToFuture()
        ticker.ctx.tickAll()

        result.value mustEqual Some(Success(false))
    }

    "Running a memoized effect after it was previously canceled reruns it" in ticked {
      implicit ticker =>
        val op = for {
          started <- Ref[IO].of(0)
          completed <- Ref[IO].of(0)
          action = started.update(_ + 1) >> IO.sleep(200.millis) >> completed.update(_ + 1)
          memoized <- Concurrent[IO].memoize(action)
          fiber <- memoized.start
          _ <- IO.sleep(100.millis)
          _ <- fiber.cancel
          _ <- memoized.timeout(1.second)
          v1 <- started.get
          v2 <- completed.get
        } yield v1 -> v2

        val result = op.unsafeToFuture()
        ticker.ctx.tickAll()

        result.value mustEqual Some(Success((2, 1)))
    }

    "Attempting to cancel a memoized effect with active subscribers is a no-op" in ticked {
      implicit ticker =>
        val op = for {
          condition <- Deferred[IO, Unit]
          action = IO.sleep(200.millis) >> condition.complete(())
          memoized <- Concurrent[IO].memoize(action)
          fiber1 <- memoized.start
          _ <- IO.sleep(50.millis)
          fiber2 <- memoized.start
          _ <- IO.sleep(50.millis)
          _ <- fiber1.cancel
          _ <- fiber2.join // Make sure no exceptions are swallowed by start
          v <- condition.get.timeout(1.second).as(true)
        } yield v

        val result = op.unsafeToFuture()
        ticker.ctx.tickAll()

        result.value mustEqual Some(Success(true))
    }

    "Monad transformers" >> {

      "OptionT" in ticked {
        implicit ticker =>
          val op = for {
            counter <- IO.ref(0)
            incr = counter.update(_ + 1)
            optMemoOpt <- Concurrent[OptionT[IO, *]].memoize[Int](
              OptionT.liftF(incr) *> OptionT.none[IO, Int]
            ).value
            memoOpt <- optMemoOpt.fold(IO.raiseError[OptionT[IO, Int]](new Exception))(IO.pure(_))
            opt1 <- memoOpt.value
            opt2 <- memoOpt.value
            vOpt <- counter.get
          } yield (opt1 : Option[Int], opt2 : Option[Int], vOpt : Int)

          val result = op.unsafeToFuture()
          ticker.ctx.tickAll()

          result.value mustEqual Some(Success((None, None, 1)))
      }

      "EitherT" in ticked {
        implicit ticker =>
          val op = for {
            counter <- IO.ref(0)
            incr = counter.update(_ + 1)
            eitMemoEit <- Concurrent[EitherT[IO, String, *]].memoize[Int](
              EitherT.liftF[IO, String, Unit](incr) *> EitherT.left(IO.pure("x"))
            ).value
            memoEit <- eitMemoEit.fold(_ => IO.raiseError(new Exception), IO.pure(_))
            eit1 <- memoEit.value
            eit2 <- memoEit.value
            vEit <- counter.get
          } yield (eit1 : Either[String, Int], eit2 : Either[String, Int], vEit : Int)

          val result = op.unsafeToFuture()
          ticker.ctx.tickAll()

          result.value mustEqual Some(Success((Left("x"), Left("x"), 1)))
      }

      "IorT" in ticked {
        implicit ticker =>
          val op = for {
            counter <- IO.ref(0)
            incr = counter.update(_ + 1)
            // left:
            iorMemoIor1 <- Concurrent[IorT[IO, String, *]].memoize[Int](
              IorT.liftF[IO, String, Unit](incr) *> IorT.left[Int](IO.pure("x"))
            ).value
            memoIor1 <- iorMemoIor1.fold(_ => IO.raiseError[IorT[IO, String, Int]](new Exception), IO.pure(_), (_, _) => IO.raiseError(new Exception))
            ior1 <- memoIor1.value
            ior2 <- memoIor1.value
            vIor1 <- counter.get
            // both:
            iorMemoIor2 <- Concurrent[IorT[IO, String, *]].memoize[Int](
              IorT.liftF[IO, String, Unit](incr) *> IorT.both[IO, String, Int](IO.pure("x"), IO.pure(42))
            ).value
            memoIor2 <- iorMemoIor2.fold(_ => IO.raiseError[IorT[IO, String, Int]](new Exception), IO.pure(_), (_, _) => IO.raiseError(new Exception))
            ior3 <- memoIor2.value
            ior4 <- memoIor2.value
            vIor2 <- counter.get
          } yield (
            ior1 : Ior[String, Int],
            ior2 : Ior[String, Int],
            vIor1 : Int,
            ior3 : Ior[String, Int],
            ior4 : Ior[String, Int],
            vIor2 : Int,
          )

          val result = op.unsafeToFuture()
          ticker.ctx.tickAll()

          result.value mustEqual Some(Success((
            Ior.left("x"),
            Ior.left("x"),
            1,
            Ior.both("x", 42),
            Ior.both("x", 42),
            2,
          )))
      }

      "WriterT" in ticked {
        implicit ticker =>
          val op = for {
            counter <- IO.ref(0)
            incr = counter.update(_ + 1)
            wriMemoWri <- Concurrent[WriterT[IO, List[String], *]].memoize[Int](
              WriterT.liftF[IO, List[String], Unit](incr) *> WriterT(IO.pure((List("x"), 42)))
            ).run
            (log, memoWri) = wriMemoWri
            _ <- if (log.nonEmpty) IO.raiseError(new Exception) else IO.unit
            wri1 <- memoWri.run
            wri2 <- memoWri.run
            vWri <- counter.get
          } yield (
            wri1 : (List[String], Int),
            wri2 : (List[String], Int),
            vWri : Int,
          )

          val result = op.unsafeToFuture()
          ticker.ctx.tickAll()

          result.value mustEqual Some(Success((
            (List("x"), 42),
            (List("x"), 42),
            1,
          )))
      }
    }
  }

}
