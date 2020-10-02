/*
 * Copyright 2020 Typelevel
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

import cats.effect.kernel.Effect
import cats.syntax.all._

// import org.specs2.matcher.MatchResult
import org.specs2.specification.core.Fragments

import scala.concurrent.duration._

class SemaphoreSpec extends BaseSpec { outer =>

  sequential

  "semaphore" should {

    tests("async", n => Semaphore[IO](n))
    tests("async in", n => Semaphore.in[IO, IO](n))
    tests("async imapK", n => Semaphore[IO](n).map(_.imapK[IO](Effect[IO].toK, Effect[IO].toK)))

    "acquire does not leak permits upon cancelation" in real {
      val op = Semaphore[IO](1L).flatMap { s =>
        // acquireN(2) will get 1 permit and then timeout waiting for another,
        // which should restore the semaphore count to 1. We then release a permit
        // bringing the count to 2. Since the old acquireN(2) is canceled, the final
        // count stays at 2.
        s.acquireN(2L).timeout(1.milli).attempt *> s.release *> IO.sleep(10.millis) *> s.count
      }

      op.flatMap { res =>
        IO {
          res must beEqualTo(2: Long)
        }
      }
    }

    "withPermit does not leak fibers or permits upon cancelation" in real {
      val op = Semaphore[IO](0L).flatMap { s =>
        // The inner s.release should never be run b/c the timeout will be reached before a permit
        // is available. After the timeout and hence cancelation of s.withPermit(...), we release
        // a permit and then sleep a bit, then check the permit count. If withPermit doesn't properly
        // cancel, the permit count will be 2, otherwise 1
        s.withPermit(s.release).timeout(1.milli).attempt *> s.release *> IO.sleep(
          10.millis) *> s.count
      }

      op.flatMap { res =>
        IO {
          res must beEqualTo(1: Long)
        }
      }
    }

  }

  //TODO this requires background, which in turn requires Resource
  // private def withLock[T](n: Long, s: Semaphore[IO], check: IO[T]): IO[(Long, T)] =
  //   s.acquireN(n).background.use { _ =>
  //     //w/o cs.shift this hangs for coreJS
  //     s.count.iterateUntil(_ < 0).flatMap(t => check.tupleLeft(t))
  //   }

  def tests(label: String, sc: Long => IO[Semaphore[IO]]): Fragments = {
    s"$label - do not allow negative n" in real {
      val op = sc(-42).attempt

      op.flatMap { res =>
        IO {
          res must beLike {
            case Left(e) => e must haveClass[IllegalArgumentException]
          }
        }
      }
    }

    s"$label - acquire n synchronously" in real {
      val n = 20
      val op = sc(20).flatMap { s =>
        (0 until n).toList.traverse(_ => s.acquire).void *> s.available
      }

      op.flatMap { res =>
        IO {
          res must beEqualTo(0: Long)
        }
      }
    }

    //TODO this requires background, which in turn requires Resource
    // s"$label - available with no available permits" in {
    //   val n = 20L
    //   val op = sc(n)
    //     .flatMap { s =>
    //       for {
    //         _ <- s.acquire.replicateA(n.toInt)
    //         res <- withLock(1, s, s.available)
    //       } yield res

    //     }

    //   op must completeAs((-1, 0))
    // }

    //TODO this requires background, which in turn requires Resource
    // test(s"$label - available with no available permits") {
    //   val n = 20L
    //   sc(n)
    //     .flatMap { s =>
    //       for {
    //         _ <- s.acquire.replicateA(n.toInt)
    //         res <- withLock(1, s, s.available)
    //       } yield res

    //     }
    //     .unsafeToFuture()
    //     .map(_ shouldBe ((-1, 0)))
    // }

    s"$label - tryAcquire with available permits" in real {
      val n = 20
      val op = sc(30).flatMap { s =>
        for {
          _ <- (0 until n).toList.traverse(_ => s.acquire).void
          t <- s.tryAcquire
        } yield t
      }

      op.flatMap { res =>
        IO {
          res must beTrue
        }
      }
    }

    s"$label - tryAcquire with no available permits" in real {
      val n = 20
      val op = sc(20).flatMap { s =>
        for {
          _ <- (0 until n).toList.traverse(_ => s.acquire).void
          t <- s.tryAcquire
        } yield t
      }

      op.flatMap { res =>
        IO {
          res must beFalse
        }
      }
    }

    s"$label - tryAcquireN all available permits" in real {
      val n = 20
      val op = sc(20).flatMap { s =>
        for {
          t <- s.tryAcquireN(n.toLong)
        } yield t
      }

      op.flatMap { res =>
        IO {
          res must beTrue
        }
      }
    }

    //TODO requires NonEmptyParallel for IO
    // test(s"$label - offsetting acquires/releases - acquires parallel with releases") {
    //   testOffsettingReleasesAcquires((s, permits) => permits.traverse(s.acquireN).void,
    //                                  (s, permits) => permits.reverse.traverse(s.releaseN).void)
    // }

    //TODO requires NonEmptyParallel for IO
    // test(s"$label - offsetting acquires/releases - individual acquires/increment in parallel") {
    //   testOffsettingReleasesAcquires((s, permits) => permits.parTraverse(s.acquireN).void,
    //                                  (s, permits) => permits.reverse.parTraverse(s.releaseN).void)
    // }

    s"$label - available with available permits" in real {
      val op = sc(20).flatMap { s =>
        for {
          _ <- s.acquireN(19)
          t <- s.available
        } yield t
      }

      op.flatMap { res =>
        IO {
          res must beEqualTo(1: Long)
        }
      }
    }

    s"$label - available with 0 available permits" in real {
      val op = sc(20).flatMap { s =>
        for {
          _ <- s.acquireN(20).void
          t <- IO.cede *> s.available
        } yield t
      }

      op.flatMap { res =>
        IO {
          res must beEqualTo(0: Long)
        }
      }
    }

    s"$label - count with available permits" in real {
      val n = 18
      val op = sc(20).flatMap { s =>
        for {
          _ <- (0 until n).toList.traverse(_ => s.acquire).void
          a <- s.available
          t <- s.count
        } yield (a, t)
      }

      op.flatMap { res =>
        IO {
          res must beLike {
            case (available, count) => available must beEqualTo(count)
          }
        }
      }
    }

    //TODO this requires background, which in turn requires Resource
    // s"$label - count with no available permits" in {
    //   val n: Long = 8
    //   val op = sc(n)
    //     .flatMap { s =>
    //       for {
    //         _ <- s.acquireN(n).void
    //         res <- withLock(n, s, s.count)
    //       } yield res
    //     }

    //   op must completeAs((-n, n))
    // }

    s"$label - count with 0 available permits" in real {
      val op = sc(20).flatMap { s =>
        for {
          _ <- s.acquireN(20).void
          x <- (IO.cede *> s.count).start
          t <- x.join
        } yield t
      }

      op.flatMap {
        case Outcome.Succeeded(ioa) =>
          ioa.flatMap { res =>
            IO {
              res must beEqualTo(0: Long)
            }
          }
        case _ => IO.pure(false must beTrue) //Is there a not a `const failure` matcher?
      }
    }

  }

  //TODO requires NonEmptyParallel for IO
  // def testOffsettingReleasesAcquires(sc: Long => IO[Semaphore[IO]],
  //                                    acquires: (Semaphore[IO], Vector[Long]) => IO[Unit],
  //                                    releases: (Semaphore[IO], Vector[Long]) => IO[Unit]): MatchResult[IO[Long]] = {
  //   val permits: Vector[Long] = Vector(1, 0, 20, 4, 0, 5, 2, 1, 1, 3)
  //   val op = sc(0)
  //     .flatMap { s =>
  //       (acquires(s, permits), releases(s, permits)).parTupled *> s.count
  //     }

  //   op must completeAs(0: Long)
  // }

}
