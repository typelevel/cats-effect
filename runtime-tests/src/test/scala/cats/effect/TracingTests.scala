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

import cats.syntax.all._
import cats.effect.tracing.IOTrace

import scala.concurrent.ExecutionContext
import scala.util.control.NoStackTrace

class TracingTests extends CatsEffectSuite {
  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global
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
      assertEquals(r.captured, 4)
    }
  }

  test("enhanced exceptions are not augmented more than once") {
    val task = for {
      _ <- IO.pure(1)
      _ <- IO.pure(2)
      _ <- IO.pure(3)
      _ <- IO.shift(executionContext)
      _ <- IO.pure(1)
      _ <- IO.pure(2)
      _ <- IO.pure(3)
      e1 <- IO.raiseError(new Throwable("Encountered an error")).attempt
      e2 <- IO.pure(e1).rethrow.attempt
    } yield (e1, e2)

    for (r <- task.unsafeToFuture()) yield {
      val (e1, e2) = r
      assertEquals(e1.swap.toOption.get.getStackTrace.length, e2.swap.toOption.get.getStackTrace.length)
    }
  }

  test("enhanced exceptions is not applied when stack trace is empty") {
    val task = for {
      e1 <- IO.raiseError(new EmptyException).attempt
    } yield e1

    for (r <- task.unsafeToFuture()) yield {
      assertEquals(r.swap.toOption.get.getStackTrace.length, 0)
    }
  }

  class EmptyException extends NoStackTrace
}
