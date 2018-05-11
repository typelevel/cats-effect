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

package cats
package effect
package concurrent

import cats.implicits._
import org.scalatest.{AsyncFunSuite, EitherValues, Matchers}
import scala.concurrent.ExecutionContext

class SemaphoreTests extends AsyncFunSuite with Matchers with EitherValues {

  implicit override def executionContext: ExecutionContext = ExecutionContext.Implicits.global

  def tests(label: String, sc: Long => IO[Semaphore[IO]]): Unit = {
    test(s"$label - acquire n synchronously") {
      val n = 20
      sc(20).flatMap { s =>
        (0 until n).toList.traverse(_ => s.acquire).void *> s.available
      }.unsafeToFuture.map(_ shouldBe 0)
    }

    test(s"$label - offsetting acquires/releases - acquires parallel with releases") {
      testOffsettingReleasesAcquires(
        (s, permits) => permits.traverse(s.acquireN).void,
        (s, permits) => permits.reverse.traverse(s.releaseN).void)
    }

    test(s"$label - offsetting acquires/releases - individual acquires/increment in parallel") {
      testOffsettingReleasesAcquires(
        (s, permits) => Parallel.parTraverse(permits)(IO.shift *> s.acquireN(_)).void,
        (s, permits) => Parallel.parTraverse(permits.reverse)(IO.shift *> s.releaseN(_)).void)
    }

    def testOffsettingReleasesAcquires(acquires: (Semaphore[IO], Vector[Long]) => IO[Unit], releases: (Semaphore[IO], Vector[Long]) => IO[Unit]) = {
      val permits: Vector[Long] = Vector(1, 0, 20, 4, 0, 5, 2, 1, 1, 3)
      sc(0).flatMap { s =>
        for {
          dfib <- (IO.shift *> acquires(s, permits)).start
          ifib <- (IO.shift *> releases(s, permits)).start
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
