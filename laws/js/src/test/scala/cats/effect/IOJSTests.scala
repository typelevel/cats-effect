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

import org.scalatest.matchers.should.Matchers
import org.scalatest.funsuite.AsyncFunSuite

import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js.timers.setTimeout

class IOJSTests extends AsyncFunSuite with Matchers {
  implicit override def executionContext =
    ExecutionContext.global

  def delayed[A](duration: FiniteDuration)(f: => A): IO[A] =
    IO.async { callback =>
      setTimeout(duration.toMillis.toDouble)(callback(Right(f)))
    }

  test("unsafeToFuture works") {
    delayed(100.millis)(10).unsafeToFuture().map { r =>
      r shouldEqual 10
    }
  }

  test("unsafeRunSync is unsupported for async stuff") {
    Future {
      try {
        delayed(100.millis)(10).unsafeRunSync()
        fail("Expected UnsupportedOperationException")
      } catch {
        case _: UnsupportedOperationException =>
          succeed
      }
    }
  }
}
