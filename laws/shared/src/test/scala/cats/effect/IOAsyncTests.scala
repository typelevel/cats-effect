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

import org.scalactic.source.Position
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers
import org.scalatest.funsuite.AsyncFunSuite
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Tests doing real asynchrony for both the JVM and JS, by
 * means of ScalaTest's engine.
 */
class IOAsyncTests extends AsyncFunSuite with Matchers {
  implicit override def executionContext =
    ExecutionContext.global
  implicit val timer: Timer[IO] =
    IO.timer(executionContext)
  implicit val cs: ContextShift[IO] =
    IO.contextShift(executionContext)

  def testEffectOnRunAsync(source: IO[Int], expected: Try[Int])(implicit pos: Position): Future[Assertion] = {
    val effect = Promise[Int]()
    val attempt = Promise[Try[Int]]()
    effect.future.onComplete(attempt.success)

    val io = source.runAsync {
      case Right(a) => IO { effect.success(a); () }
      case Left(e)  => IO { effect.failure(e); () }
    }

    for (_ <- io.toIO.unsafeToFuture(); v <- attempt.future) yield {
      v shouldEqual expected
    }
  }

  test("IO.pure#runAsync") {
    testEffectOnRunAsync(IO.pure(10), Success(10))
  }

  test("IO.apply#runAsync") {
    testEffectOnRunAsync(IO(10), Success(10))
  }

  test("IO.apply#shift#runAsync") {
    testEffectOnRunAsync(IO.shift.flatMap(_ => IO(10)), Success(10))
  }

  test("IO.raiseError#runAsync") {
    val dummy = new RuntimeException("dummy")
    testEffectOnRunAsync(IO.raiseError(dummy), Failure(dummy))
  }

  test("IO.raiseError#shift#runAsync") {
    val dummy = new RuntimeException("dummy")
    testEffectOnRunAsync(IO.shift.flatMap(_ => IO.raiseError(dummy)), Failure(dummy))
  }

  test("IO.sleep(10.ms)") {
    val io = IO.sleep(10.millis).map(_ => 10)

    for (r <- io.unsafeToFuture()) yield {
      r shouldBe 10
    }
  }

  test("IO.sleep(negative)") {
    val io = IO.sleep(-10.seconds).map(_ => 10)

    for (r <- io.unsafeToFuture()) yield {
      r shouldBe 10
    }
  }
}
