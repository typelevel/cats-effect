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

import cats.effect.tracing.IOTrace
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.ExecutionContext

class CachedStackTracingTests extends AsyncFunSuite with Matchers {
  implicit override def executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  def traced[A](io: IO[A]): IO[IOTrace] =
    io.flatMap(_ => IO.trace)

  test("cached stack tracing captures map frames") {
    val task = IO.pure(0).map(_ + 1).map(_ + 1)

    for (r <- traced(task).unsafeToFuture()) yield {
      r.captured shouldBe 3
      r.frames.filter(_.tag == 4).length shouldBe 2
    }
  }

  test("cached stack tracing captures bind frames") {
    val task = IO.pure(0).flatMap(a => IO(a + 1)).flatMap(a => IO(a + 1))

    for (r <- traced(task).unsafeToFuture()) yield {
      r.captured shouldBe 3
      r.frames.filter(_.tag == 3).length shouldBe 3 // extra one is to capture the trace
    }
  }

  test("cached stack tracing captures async frames") {
    val task = IO.async[Int](_(Right(0))).flatMap(a => IO(a + 1)).flatMap(a => IO(a + 1))

    for (r <- traced(task).unsafeToFuture()) yield {
      r.captured shouldBe 4
      r.frames.filter(_.tag == 5).length shouldBe 1
    }
  }
}
