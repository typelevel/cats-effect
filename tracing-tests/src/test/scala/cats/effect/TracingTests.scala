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

class TracingTests extends AsyncFunSuite with Matchers {
  implicit override def executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  def traced[A](io: IO[A]): IO[IOTrace] =
    io.flatMap(_ => IO.trace)

  test("traces are preserved across asynchronous boundaries") {
    val task = for {
      a <- IO.pure(1)
      _ <- IO.shift
      b <- IO.pure(1)
    } yield a + b

    for (r <- traced(task).unsafeToFuture()) yield {
      r.captured shouldBe 4
    }
  }
}
