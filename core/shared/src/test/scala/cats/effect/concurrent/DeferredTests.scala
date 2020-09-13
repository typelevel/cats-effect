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
package concurrent

import cats.syntax.all._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class DeferredTests extends CatsEffectSuite {
  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val timer: cats.effect.Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  trait DeferredConstructor { def apply[A]: IO[Deferred[IO, A]] }
  trait TryableDeferredConstructor { def apply[A]: IO[TryableDeferred[IO, A]] }

  def tests(label: String, pc: DeferredConstructor): Unit = {
    test(s"$label - complete") {
      pc[Int]
        .flatMap { p =>
          p.complete(0) *> p.get
        }
        .unsafeToFuture()
        .map(assertEquals(_, 0))
    }

    test(s"$label - complete is only successful once") {
      pc[Int]
        .flatMap { p =>
          (p.complete(0) *> p.complete(1).attempt).product(p.get)
        }
        .unsafeToFuture()
        .map {
          case (err, value) =>
            assert(err.swap.toOption.get.isInstanceOf[IllegalStateException])
            assertEquals(value, 0)
        }
    }

    test(s"$label - get blocks until set") {
      val op = for {
        state <- Ref[IO].of(0)
        modifyGate <- pc[Unit]
        readGate <- pc[Unit]
        _ <- IO.shift *> (modifyGate.get *> state.update(_ * 2) *> readGate.complete(())).start
        _ <- IO.shift *> (state.set(1) *> modifyGate.complete(())).start
        _ <- readGate.get
        res <- state.get
      } yield res
      op.unsafeToFuture().map(assertEquals(_, 2))
    }
  }

  def tryableTests(label: String, pc: TryableDeferredConstructor): Unit = {
    test(s"$label - tryGet returns None for unset Deferred") {
      pc[Unit].flatMap(_.tryGet).unsafeToFuture().map(assertEquals(_, None))
    }

    test(s"$label - tryGet returns Some() for set Deferred") {
      val op = for {
        d <- pc[Unit]
        _ <- d.complete(())
        result <- d.tryGet
      } yield assertEquals(result, Some(()))

      op.unsafeToFuture()
    }
  }

  tests("concurrent", new DeferredConstructor { def apply[A] = Deferred[IO, A] })
  tests("concurrentTryable", new DeferredConstructor { def apply[A] = Deferred.tryable[IO, A] })
  tests("async", new DeferredConstructor { def apply[A] = Deferred.uncancelable[IO, A] })
  tests("asyncTryable", new DeferredConstructor { def apply[A] = Deferred.tryableUncancelable[IO, A] })

  tryableTests("concurrentTryable", new TryableDeferredConstructor { def apply[A] = Deferred.tryable[IO, A] })
  tryableTests("asyncTryable", new TryableDeferredConstructor { def apply[A] = Deferred.tryableUncancelable[IO, A] })

  private def cancelBeforeForcing(pc: IO[Deferred[IO, Int]]): IO[Option[Int]] =
    for {
      r <- Ref[IO].of(Option.empty[Int])
      p <- pc
      fiber <- p.get.start
      _ <- fiber.cancel
      _ <- (IO.shift *> fiber.join.flatMap(i => r.set(Some(i)))).start
      _ <- timer.sleep(100.millis)
      _ <- p.complete(42)
      _ <- timer.sleep(100.millis)
      result <- r.get
    } yield result

  test("concurrent - get - cancel before forcing") {
    cancelBeforeForcing(Deferred.apply).unsafeToFuture().map(assertEquals(_, None))
  }

  test("issue #380: complete doesn't block, test #1") {
    def execute(times: Int): IO[Unit] = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == 512) IO.async[Unit](cb => cb(Right(()))) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)

      val task = for {
        d <- Deferred[IO, Unit]
        latch <- Deferred[IO, Unit]
        fb <- (latch.complete(()) *> d.get *> foreverAsync(0)).start
        _ <- latch.get
        _ <- d.complete(()).timeout(15.seconds).guarantee(fb.cancel)
      } yield {
        assert(true)
      }

      task.flatMap { r =>
        if (times > 0) execute(times - 1)
        else IO.pure(r)
      }
    }

    execute(100).unsafeToFuture()
  }

  test("issue #380: complete doesn't block, test #2") {
    def execute(times: Int): IO[Unit] = {
      val task = for {
        d <- Deferred[IO, Unit]
        latch <- Deferred[IO, Unit]
        fb <- (latch.complete(()) *> d.get *> IO.unit.foreverM).start
        _ <- latch.get
        _ <- d.complete(()).timeout(15.seconds).guarantee(fb.cancel)
      } yield {
        assert(true)
      }

      task.flatMap { r =>
        if (times > 0) execute(times - 1)
        else IO.pure(r)
      }
    }

    execute(100).unsafeToFuture()
  }
}
