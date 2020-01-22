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

package cats
package effect
package concurrent

import cats.implicits._
import org.scalatest.{Assertion, EitherValues}
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration._

class SemaphoreTests extends AsyncFunSuite with Matchers with EitherValues {
  implicit override def executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)
  implicit val timer: Timer[IO] = IO.timer(executionContext)

  def tests(label: String, sc: Long => IO[Semaphore[IO]]): Unit = {
    test(s"$label - acquire n synchronously") {
      val n = 20
      sc(20)
        .flatMap { s =>
          (0 until n).toList.traverse(_ => s.acquire).void *> s.available
        }
        .unsafeToFuture()
        .map(_ shouldBe 0)
    }

    test(s"$label - tryAcquire with available permits") {
      val n = 20
      sc(30)
        .flatMap { s =>
          for {
            _ <- (0 until n).toList.traverse(_ => s.acquire).void
            t <- s.tryAcquire
          } yield t
        }
        .unsafeToFuture()
        .map(_ shouldBe true)
    }

    test(s"$label - tryAcquire with no available permits") {
      val n = 20
      sc(20)
        .flatMap { s =>
          for {
            _ <- (0 until n).toList.traverse(_ => s.acquire).void
            t <- s.tryAcquire
          } yield t
        }
        .unsafeToFuture()
        .map(_ shouldBe false)
    }

    test(s"$label - offsetting acquires/releases - acquires parallel with releases") {
      testOffsettingReleasesAcquires((s, permits) => permits.traverse(s.acquireN).void,
                                     (s, permits) => permits.reverse.traverse(s.releaseN).void)
    }

    test(s"$label - offsetting acquires/releases - individual acquires/increment in parallel") {
      testOffsettingReleasesAcquires((s, permits) => permits.parTraverse(s.acquireN).void,
                                     (s, permits) => permits.reverse.parTraverse(s.releaseN).void)
    }

    test(s"$label - available with available permits") {
      sc(20)
        .flatMap { s =>
          for {
            _ <- s.acquireN(19)
            t <- s.available
          } yield t
        }
        .unsafeToFuture()
        .map(_ shouldBe 1)
    }

    test(s"$label - available with no available permits") {
      sc(20)
        .flatMap { s =>
          for {
            _ <- s.acquireN(20).void
            t <- IO.shift *> s.available
          } yield t
        }
        .unsafeToFuture()
        .map(_ shouldBe 0)
    }

    test(s"$label - tryAcquireN with no available permits") {
      sc(20)
        .flatMap { s =>
          for {
            _ <- s.acquireN(20).void
            _ <- s.acquire.start
            x <- (IO.shift *> s.tryAcquireN(1)).start
            t <- x.join
          } yield t
        }
        .unsafeToFuture()
        .map(_ shouldBe false)
    }

    test(s"$label - count with available permits") {
      val n = 18
      sc(20)
        .flatMap { s =>
          for {
            _ <- (0 until n).toList.traverse(_ => s.acquire).void
            a <- s.available
            t <- s.count
          } yield (a, t)
        }
        .unsafeToFuture()
        .map { case (available, count) => available shouldBe count }
    }

    test(s"$label - count with no available permits") {
      sc(20)
        .flatMap { s =>
          for {
            _ <- s.acquireN(20).void
            x <- (IO.shift *> s.count).start
            t <- x.join
          } yield t
        }
        .unsafeToFuture()
        .map(count => count shouldBe 0)
    }

    def testOffsettingReleasesAcquires(acquires: (Semaphore[IO], Vector[Long]) => IO[Unit],
                                       releases: (Semaphore[IO], Vector[Long]) => IO[Unit]): Future[Assertion] = {
      val permits: Vector[Long] = Vector(1, 0, 20, 4, 0, 5, 2, 1, 1, 3)
      sc(0)
        .flatMap { s =>
          (acquires(s, permits), releases(s, permits)).parTupled *> s.count
        }
        .unsafeToFuture()
        .map(_ shouldBe 0L)
    }
  }

  tests("concurrent", n => Semaphore[IO](n))

  test("concurrent - acquire does not leak permits upon cancelation") {
    Semaphore[IO](1L)
      .flatMap { s =>
        // acquireN(2) will get 1 permit and then timeout waiting for another,
        // which should restore the semaphore count to 1. We then release a permit
        // bringing the count to 2. Since the old acquireN(2) is canceled, the final
        // count stays at 2.
        s.acquireN(2L).timeout(1.milli).attempt *> s.release *> IO.sleep(10.millis) *> s.count
      }
      .unsafeToFuture()
      .map(_ shouldBe 2L)
  }

  test("concurrent - withPermit does not leak fibers or permits upon cancelation") {
    Semaphore[IO](0L)
      .flatMap { s =>
        // The inner s.release should never be run b/c the timeout will be reached before a permit
        // is available. After the timeout and hence cancelation of s.withPermit(...), we release
        // a permit and then sleep a bit, then check the permit count. If withPermit doesn't properly
        // cancel, the permit count will be 2, otherwise 1
        s.withPermit(s.release).timeout(1.milli).attempt *> s.release *> IO.sleep(10.millis) *> s.count
      }
      .unsafeToFuture()
      .map(_ shouldBe 1L)
  }

  tests("async", n => Semaphore.uncancelable[IO](n))
}
