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

package cats.effect

import concurrent.Deferred
import cats.implicits._
import org.scalatest._
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, CancellationException}

class DeferredJVMTests extends FunSuite with Matchers {
  test("Deferred: issue #380 with foreverM") {
    implicit val ec: ExecutionContext = ExecutionContext.global
    implicit val cs = IO.contextShift(ec)
    implicit val timer: Timer[IO] = IO.timer(ec)

    for (_ <- 0 until 10) {
      val cancelLoop = new AtomicBoolean(false)
      val unit = IO {
        if (cancelLoop.get()) throw new CancellationException
      }

      try {
        val task = for {
          df    <- cats.effect.concurrent.Deferred[IO, Unit]
          latch <- Deferred[IO, Unit]
          _     <- (latch.complete(()) *> df.get *> unit.foreverM).start
          _     <- latch.get
          _     <- timer.sleep(100.millis)
          _     <- df.complete(())
        } yield ()

        val dt = 10.seconds
        assert(task.unsafeRunTimed(dt).nonEmpty, s"; timed-out after $dt")
      } finally {
        cancelLoop.set(true)
      }
    }
  }

  test("Deferred: issue #380 with cooperative light async boundaries") {
    implicit val ec: ExecutionContext = ExecutionContext.global
    implicit val cs = IO.contextShift(ec)
    implicit val timer: Timer[IO] = IO.timer(ec)

    def run = {
      def foreverAsync(i: Int): IO[Unit] = {
        if(i == 512) IO.async[Unit](cb => cb(Right(()))) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)
      }

      for {
        d     <- Deferred[IO, Unit]
        latch <- Deferred[IO, Unit]
        fb    <- (latch.complete(()) *> d.get *> foreverAsync(0)).start
        _     <- latch.get
        _     <- timer.sleep(100.millis)
        _     <- d.complete(()).timeout(5.seconds).guarantee(fb.cancel)
      } yield true
    }

    assert(run.unsafeRunSync(), s"timed out")
  }

  test("Deferred: issue #380 with cooperative full async boundaries") {
    implicit val ec: ExecutionContext = ExecutionContext.global
    implicit val cs = IO.contextShift(ec)
    implicit val timer: Timer[IO] = IO.timer(ec)

    def run = {
      def foreverAsync(i: Int): IO[Unit] = {
        if(i == 512) IO.unit.start.flatMap(_.join) >> foreverAsync(0)
        else IO.unit >> foreverAsync(i + 1)
      }

      for {
        d     <- Deferred[IO, Unit]
        latch <- Deferred[IO, Unit]
        fb    <- (latch.complete(()) *> d.get *> foreverAsync(0)).start
        _     <- latch.get
        _     <- timer.sleep(100.millis)
        _     <- d.complete(()).timeout(5.seconds).guarantee(fb.cancel)
      } yield true
    }

    assert(run.unsafeRunSync(), s"timed out")
  }
}
