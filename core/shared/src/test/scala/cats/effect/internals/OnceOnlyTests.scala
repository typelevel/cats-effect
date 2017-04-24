/*
 * Copyright 2017 Typelevel
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

package cats.effect.internals

import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.{AsyncFunSuite, Matchers}
import cats.effect.internals.IOPlatform.onceOnly
import scala.concurrent.{ExecutionContext, Future}

class OnceOnlyTests extends AsyncFunSuite with Matchers {
  override implicit val executionContext =
    ExecutionContext.global

  test("onceOnly provides idempotency guarantees for sequential execution") {
    Future.successful {
      var effect = 0
      val f: Int => Unit =
        onceOnly { x => effect += x }

      f(10)
      effect shouldEqual 10

      f(20)
      effect shouldEqual 10
    }
  }

  test("onceOnly provides idempotency guarantees for parallel execution") {
    val effect = new AtomicInteger(0)
    val f: Int => Unit =
      onceOnly { x => effect.addAndGet(x) }

    val f1 = Future(f(10))
    val f2 = Future(f(10))
    val f3 = Future(f(10))

    for (_ <- f1; _ <- f2; _ <- f3) yield {
      effect.get() shouldEqual 10
    }
  }
}
