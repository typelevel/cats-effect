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

package cats.effect

import cats.effect.tracing.{IOTrace, TraceTag}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext

class TracingTests extends AsyncFunSuite with Matchers {
  implicit override def executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  def traced[A](io: IO[A]): IO[IOTrace] =
    for {
      _ <- io.traced
      t <- IO.backtrace
    } yield t

  test("trace is empty when no traces are captured") {
    val task = for {
      _ <- IO.pure(1)
      _ <- IO.pure(1)
      t <- IO.backtrace
    } yield t

    for (r <- task.unsafeToFuture()) yield {
      r.captured shouldBe 0
    }
  }

  test("rabbit tracing captures map frames") {
    val task = IO.pure(0).map(_ + 1).map(_ + 1)

    for (r <- traced(task).unsafeToFuture()) yield {
      r.captured shouldBe 2
      r.frames.filter(_.tag == TraceTag.Map).length shouldBe 2
    }
  }

  test("rabbit tracing captures bind frames") {
    val task = IO.pure(0).flatMap(a => IO(a + 1)).flatMap(a => IO(a + 1))

    for (r <- traced(task).unsafeToFuture()) yield {
      r.captured shouldBe 2
      r.frames.filter(_.tag == TraceTag.Bind).length shouldBe 2
    }
  }

  test("rabbit tracing captures async frames") {
    val task = IO.async[Int](_(Right(0))).flatMap(a => IO(a + 1)).flatMap(a => IO(a + 1))

    for (r <- traced(task).unsafeToFuture()) yield {
      r.captured shouldBe 3
      r.frames.filter(_.tag == TraceTag.Async).length shouldBe 1
    }
  }

  test("traces are preserved across asynchronous boundaries") {
    val task = for {
      a <- IO.pure(1)
      _ <- IO.shift
      b <- IO.pure(1)
    } yield a + b

    for (r <- traced(task).unsafeToFuture()) yield {
      r.captured shouldBe 3
    }
  }

  test("traces are emptied when initiating a new trace") {
    val op = for {
      a <- IO.pure(1)
      b <- IO.pure(1)
    } yield a + b

    val task = for {
      _ <- op.traced
      _ <- IO.backtrace
      _ <- op.traced
      t <- IO.backtrace
    } yield t.frames.length

    for (r <- task.unsafeToFuture()) yield {
      r shouldBe 2
    }
  }
}
