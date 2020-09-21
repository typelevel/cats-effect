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

import cats.syntax.all._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class SemaphoreTests extends CatsEffectSuite {
  implicit val executionContext: ExecutionContext = ExecutionContext.Implicits.global
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)
  implicit val timer: Timer[IO] = IO.timer(executionContext)

  private def withLock[T](n: Long, s: Semaphore[IO], check: IO[T]): IO[(Long, T)] =
    s.acquireN(n).background.use { _ =>
      //w/o cs.shift this hangs for coreJS
      cs.shift *> s.count.iterateUntil(_ < 0).flatMap(t => check.tupleLeft(t))
    }

  def tests(label: String, sc: Long => IO[Semaphore[IO]]): Unit = {
    test(s"$label - do not allow negative n") {
      sc(-42).attempt.map { r =>
        assert(r.left.toOption.get.isInstanceOf[IllegalArgumentException])
      }
    }

    test(s"$label - acquire n synchronously") {
      val n = 20L
      for {
        s <- sc(n)
        _ <- (0L until n).toList.traverse_(_ => s.acquire)
        v <- s.available
      } yield assertEquals(v, 0L)
    }

    test(s"$label - available with no available permits") {
      val n = 20L
      for {
        s <- sc(n)
        _ <- s.acquire.replicateA(n.toInt)
        v <- withLock(1, s, s.available)
      } yield assertEquals(v, (-1L, 0L))
    }

    test(s"$label - tryAcquire with available permits") {
      val n = 20
      for {
        s <- sc(30)
        _ <- (0 until n).toList.traverse_(_ => s.acquire)
        v <- s.tryAcquire
      } yield assertEquals(v, true)
    }

    test(s"$label - tryAcquire with no available permits") {
      val n = 20
      for {
        s <- sc(20)
        _ <- (0 until n).toList.traverse_(_ => s.acquire)
        v <- s.tryAcquire
      } yield assertEquals(v, false)
    }

    test(s"$label - tryAcquireN all available permits") {
      val n = 20L
      for {
        s <- sc(n)
        v <- s.tryAcquireN(n)
      } yield assertEquals(v, true)
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
      for {
        s <- sc(20)
        _ <- s.acquireN(19)
        v <- s.available
      } yield assertEquals(v, 1L)
    }

    test(s"$label - available with 0 available permits") {
      for {
        s <- sc(20)
        _ <- s.acquireN(20)
        _ <- IO.shift
        v <- s.available
      } yield assertEquals(v, 0L)
    }

    test(s"$label - tryAcquireN with no available permits") {
      for {
        s <- sc(20)
        _ <- s.acquireN(20)
        b <- s.acquire.start
        x <- (IO.shift *> s.tryAcquireN(1)).start
        v <- x.join
        _ <- b.cancel
      } yield assertEquals(v, false)
    }

    test(s"$label - count with available permits") {
      val n = 18
      for {
        s <- sc(20)
        _ <- (0 until n).toList.traverse_(_ => s.acquire)
        a <- s.available
        t <- s.count
      } yield assertEquals(a, t)
    }

    test(s"$label - count with no available permits") {
      val n = 8L
      for {
        s <- sc(n)
        _ <- s.acquireN(n)
        v <- withLock(n, s, s.count)
      } yield assertEquals(v, (-n, -n))
    }

    test(s"$label - count with 0 available permits") {
      for {
        s <- sc(20)
        _ <- s.acquireN(20)
        x <- (IO.shift *> s.count).start
        t <- x.join
      } yield assertEquals(t, 0L)
    }

    def testOffsettingReleasesAcquires(acquires: (Semaphore[IO], Vector[Long]) => IO[Unit],
                                       releases: (Semaphore[IO], Vector[Long]) => IO[Unit]): IO[Unit] = {
      val permits: Vector[Long] = Vector(1, 0, 20, 4, 0, 5, 2, 1, 1, 3)
      for {
        s <- sc(0)
        _ <- (acquires(s, permits), releases(s, permits)).parTupled
        v <- s.count
      } yield assertEquals(v, 0L)
    }
  }

  tests("concurrent", n => Semaphore[IO](n))
  tests("concurrent in", n => Semaphore.in[IO, IO](n))
  tests("concurrent imapK", n => Semaphore[IO](n).map(_.imapK[IO](Effect.toIOK, Effect.toIOK)))

  test("concurrent - acquire does not leak permits upon cancelation") {
    for {
      s <- Semaphore[IO](1L)
      // acquireN(2) will get 1 permit and then timeout waiting for another,
      // which should restore the semaphore count to 1. We then release a permit
      // bringing the count to 2. Since the old acquireN(2) is canceled, the final
      // count stays at 2.
      _ <- s.acquireN(2L).timeout(1.milli).attempt.void
      _ <- s.release
      _ <- IO.sleep(10.millis)
      v <- s.count
    } yield assertEquals(v, 2L)
  }

  test("concurrent - withPermit does not leak fibers or permits upon cancelation") {
    for {
      s <- Semaphore[IO](0L)
      // The inner s.release should never be run b/c the timeout will be reached before a permit
      // is available. After the timeout and hence cancelation of s.withPermit(...), we release
      // a permit and then sleep a bit, then check the permit count. If withPermit doesn't properly
      // cancel, the permit count will be 2, otherwise 1
      _ <- s.withPermit(s.release).timeout(1.milli).attempt.void
      _ <- s.release
      _ <- IO.sleep(10.millis)
      v <- s.count
    } yield assertEquals(v, 1L)
  }

  tests("async", n => Semaphore.uncancelable[IO](n))
  tests("async in", n => Semaphore.uncancelableIn[IO, IO](n))
  tests("async imapK", n => Semaphore.uncancelable[IO](n).map(_.imapK[IO](Effect.toIOK, Effect.toIOK)))
}
