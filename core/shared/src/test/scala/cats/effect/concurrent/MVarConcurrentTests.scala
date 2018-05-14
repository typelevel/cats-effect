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
package concurrent

import org.scalatest.{AsyncFunSuite, Matchers}
import scala.concurrent.ExecutionContext

class MVarConcurrentTests extends AsyncFunSuite with Matchers {
  implicit override def executionContext: ExecutionContext =
    ExecutionContext.Implicits.global

  test("empty; put; take; put; take") {
    val task = for {
      av <- MVar[IO].empty[Int]
      _  <- av.put(10)
      r1 <- av.take
      _  <- av.put(20)
      r2 <- av.take
    } yield List(r1,r2)

    for (r <- task.unsafeToFuture()) yield {
      r shouldBe List(10, 20)
    }
  }

  test("empty; take; put; take; put") {
    val task = for {
      av <- MVar[IO].empty[Int]
      f1 <- av.take.start
      _  <- av.put(10)
      f2 <- av.take.start
      _  <- av.put(20)
      r1 <- f1.join
      r2 <- f2.join
    } yield List(r1,r2)

    for (r <- task.unsafeToFuture()) yield {
      r shouldBe List(10, 20)
    }
  }

  test("empty; put; put; put; take; take; take") {
    val task = for {
      av <- MVar[IO].empty[Int]
      f1 <- av.put(10).start
      f2 <- av.put(20).start
      f3 <- av.put(30).start
      r1 <- av.take
      r2 <- av.take
      r3 <- av.take
      _  <- f1.join
      _  <- f2.join
      _  <- f3.join
    } yield List(r1, r2, r3)

    for (r <- task.unsafeToFuture()) yield {
      r shouldBe List(10, 20, 30)
    }
  }

  test("empty; take; take; take; put; put; put") {
    val task = for {
      av <- MVar[IO].empty[Int]
      f1 <- av.take.start
      f2 <- av.take.start
      f3 <- av.take.start
      _  <- av.put(10)
      _  <- av.put(20)
      _  <- av.put(30)
      r1 <- f1.join
      r2 <- f2.join
      r3 <- f3.join
    } yield List(r1, r2, r3)

    for (r <- task.unsafeToFuture()) yield {
      r shouldBe List(10, 20, 30)
    }
  }

  test("initial; take; put; take") {
    val task = for {
      av <- MVar[IO].init(10)
      r1 <- av.take
      _  <- av.put(20)
      r2 <- av.take
    } yield List(r1,r2)

    for (v <- task.unsafeToFuture()) yield {
      v shouldBe List(10, 20)
    }
  }

  test("initial; read; take") {
    val task = for {
      av <- MVar[IO].init(10)
      read <- av.read
      take <- av.take
    } yield read + take

    for (v <- task.unsafeToFuture()) yield {
      v shouldBe 20
    }
  }
}
