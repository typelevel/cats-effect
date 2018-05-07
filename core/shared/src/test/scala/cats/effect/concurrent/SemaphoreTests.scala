/*
 * The MIT License (MIT)
 * 
 * Copyright (c) 2013-2018 Paul Chiusano, and respective contributors 
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package cats
package effect
package concurrent

import cats.implicits._
import org.scalatest.{AsyncFunSuite, EitherValues, Matchers}
import scala.concurrent.ExecutionContext

class SemaphoreTests extends AsyncFunSuite with Matchers with EitherValues {

  implicit override def executionContext: ExecutionContext = ExecutionContext.Implicits.global

  def tests(label: String, sc: Long => IO[Semaphore[IO]]): Unit = {
    test(s"$label - decrement n synchronously") {
      val n = 20
      sc(20).flatMap { s =>
        (0 until n).toList.traverse(_ => s.decrement).void *> s.available
      }.unsafeToFuture.map(_ shouldBe 0)
    }

    test(s"$label - offsetting decrements/increments - decrements parallel with increments") {
      testOffsettingIncrementsDecrements(
        (s, permits) => permits.traverse(s.decrementBy).void,
        (s, permits) => permits.reverse.traverse(s.incrementBy).void)
    }

    test(s"$label - offsetting decrements/increments - individual decrements/increment in parallel") {
      testOffsettingIncrementsDecrements(
        (s, permits) => Parallel.parTraverse(permits)(IO.shift *> s.decrementBy(_)).void,
        (s, permits) => Parallel.parTraverse(permits.reverse)(IO.shift *> s.incrementBy(_)).void)
    }

    def testOffsettingIncrementsDecrements(decrements: (Semaphore[IO], Vector[Long]) => IO[Unit], increments: (Semaphore[IO], Vector[Long]) => IO[Unit]) = {
      val permits: Vector[Long] = Vector(1, 0, 20, 4, 0, 5, 2, 1, 1, 3)
      sc(0).flatMap { s =>
        for {
          dfib <- (IO.shift *> decrements(s, permits)).start
          ifib <- (IO.shift *> increments(s, permits)).start
          _ <- dfib.join
          _ <- ifib.join
          cnt <- s.count
        } yield cnt
      }.unsafeToFuture.map(_ shouldBe 0L)
    }
  }

  tests("concurrent", n => Semaphore[IO](n))
  tests("async", n => Semaphore.async[IO](n))
}
