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

class FullStackTracingTests extends AsyncFunSuite with Matchers {
  implicit override def executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  def traced[A](io: IO[A]): IO[IOTrace] =
    for {
      _ <- io.traced
      t <- IO.trace
    } yield t

  test("full stack tracing captures map frames") {
    val task = IO.pure(0).map(_ + 1).map(_ + 1)

    for (r <- traced(task).unsafeToFuture()) yield {
      r.captured shouldBe 3
      r.frames.filter(_.tag == TraceTag.Map).length shouldBe 2
    }
  }

  test("full stack tracing captures bind frames") {
    val task = IO.pure(0).flatMap(a => IO(a + 1)).flatMap(a => IO(a + 1))

    for (r <- traced(task).unsafeToFuture()) yield {
      r.captured shouldBe 5
      r.frames.filter(_.tag == TraceTag.Bind).length shouldBe 2
    }
  }

  test("full stack tracing captures async frames") {
    val task = IO.async[Int](_(Right(0))).flatMap(a => IO(a + 1)).flatMap(a => IO(a + 1))

    for (r <- traced(task).unsafeToFuture()) yield {
      r.captured shouldBe 5
      r.frames.filter(_.tag == TraceTag.Async).length shouldBe 1
    }
  }

  test("full stack tracing captures pure frames") {
    val task = IO.pure(0).flatMap(a => IO.pure(a + 1))

    for (r <- traced(task).unsafeToFuture()) yield {
      r.captured shouldBe 3
      r.frames.filter(_.tag == TraceTag.Pure).length shouldBe 2
    }
  }

  test("full stack tracing captures delay frames") {
    val task = IO(0).flatMap(a => IO(a + 1))

    for (r <- traced(task).unsafeToFuture()) yield {
      r.captured shouldBe 3
      r.frames.filter(_.tag == TraceTag.Delay).length shouldBe 2
    }
  }

  test("full stack tracing captures suspend frames") {
    val task = IO.suspend(IO(1)).flatMap(a => IO.suspend(IO(a + 1)))

    for (r <- traced(task).unsafeToFuture()) yield {
      r.captured shouldBe 5
      r.frames.filter(_.tag == TraceTag.Suspend).length shouldBe 2
    }
  }
}
