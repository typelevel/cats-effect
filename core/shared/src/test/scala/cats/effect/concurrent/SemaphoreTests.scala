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
import org.scalatest.{Assertion, AsyncFunSuite, EitherValues, Matchers}

import scala.concurrent.{ExecutionContext, Future}

class SemaphoreTests extends AsyncFunSuite with Matchers with EitherValues {

  implicit override def executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  def tests(label: String, sc: Long => IO[Semaphore[IO]]): Unit = {
    test(s"$label - acquire n synchronously") {
      val n = 20
      sc(20).flatMap { s =>
        (0 until n).toList.traverse(_ => s.acquire).void *> s.available
      }.unsafeToFuture.map(_ shouldBe 0)
    }

    test(s"$label - tryAcquire with available permits") {
      val n = 20
      sc(30).flatMap { s =>
        for {
          _ <- (0 until n).toList.traverse(_ => s.acquire).void
          t <- s.tryAcquire
        } yield t
      }.unsafeToFuture.map(_ shouldBe true)
    }

    test(s"$label - tryAcquire with no available permits") {
      val n = 20
      sc(20).flatMap { s =>
        for {
          _ <- (0 until n).toList.traverse(_ => s.acquire).void
          t <- s.tryAcquire
        } yield t
      }.unsafeToFuture.map(_ shouldBe false)
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

    test(s"$label - available with available permits") {
      sc(20).flatMap{ s =>
        for{
          _ <- s.acquireN(19)
          t <- s.available
        } yield t
      }.unsafeToFuture().map(_ shouldBe 1)
    }

    test(s"$label - available with no available permits") {
      sc(20).flatMap{ s =>
        for{
          _ <- s.acquireN(21).void.start
          t <- IO.shift *> s.available
        } yield t

      }.unsafeToFuture().map(_ shouldBe 0)
    }

    test(s"$label - tryAcquireN with no available permits") {
      sc(20).flatMap{ s =>
        for{
          _ <- s.acquireN(20).void
          _ <- s.acquire.start
          x <- (IO.shift *> s.tryAcquireN(1)).start
          t <- x.join
        } yield t
      }.unsafeToFuture().map(_ shouldBe false)
    }

    test(s"$label - count with available permits") {
      val n = 18
      sc(20).flatMap { s =>
        for {
          _ <- (0 until n).toList.traverse(_ => s.acquire).void
          a <- s.available
          t <- s.count
        } yield (a, t)
      }.unsafeToFuture().map{ case (available, count) => available shouldBe count }
    }

    test(s"$label - count with no available permits") {
      sc(20).flatMap { s =>
        for {
          _ <- s.acquireN(21).void.start
          x <- (IO.shift *> s.count).start
          t <- x.join
        } yield t
      }.unsafeToFuture().map( count =>  count shouldBe -1)
    }

    def testOffsettingReleasesAcquires(acquires: (Semaphore[IO], Vector[Long]) => IO[Unit], releases: (Semaphore[IO], Vector[Long]) => IO[Unit]): Future[Assertion] = {
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
  tests("async", n => Semaphore.uncancelable[IO](n))
}
