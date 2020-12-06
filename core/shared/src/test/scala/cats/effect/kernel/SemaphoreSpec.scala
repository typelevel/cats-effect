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
package std

import cats.arrow.FunctionK
import cats.syntax.all._

// import org.specs2.matcher.MatchResult
import org.specs2.specification.core.Fragments

import scala.concurrent.duration._

class SemaphoreSpec extends BaseSpec { outer =>

  sequential

  "semaphore" should {

    tests("async", n => Semaphore[IO](n))
    tests("async in", n => Semaphore.in[IO, IO](n))
//    tests("async mapK", n => Semaphore[IO](n).map(_.mapK[IO](FunctionK.id))) TODO

    "acquire does not leak permits upon cancelation" in real {
      val op = Semaphore[IO](1L).flatMap { s =>
        // acquireN(2) will get 1 permit and then timeout waiting for another,
        // which should restore the semaphore count to 1. We then release a permit
        // bringing the count to 2. Since the old acquireN(2) is canceled, the final
        // count stays at 2.
        s.acquireN(2L).timeout(1.milli).attempt *> s.release *> IO.sleep(10.millis) *> s.count
      }

      op.mustEqual(2L)
    }

    "permit.use does not leak fibers or permits upon cancelation" in real {
      val op = Semaphore[IO](0L).flatMap { s =>
        // The inner s.release should never be run b/c the timeout will be reached before a permit
        // is available. After the timeout and hence cancelation of s.permit.use(_ => ...), we release
        // a permit and then sleep a bit, then check the permit count. If permit.use doesn't properly
        // cancel, the permit count will be 2, otherwise 1
        s.permit.use(_ => s.release).timeout(1.milli).attempt *> s.release *> IO.sleep(
          10.millis) *> s.count
      }

      op.mustEqual(1L)
    }
  }


  def tests(label: String, sc: Long => IO[Semaphore[IO]]): Fragments = {
    s"$label - throw on negative n" in real {
      val op = IO(sc(-42))

      op.mustFailWith[IllegalArgumentException]
    }

    s"$label - block if no permits available" in ticked { implicit ticker =>
      sc(0).flatMap { sem => sem.permit.surround(IO.unit) } must nonTerminate
    }

    s"$label - execute action if permit is available for it" in real {
      sc(1).flatMap { sem => sem.permit.surround(IO.unit).mustEqual(()) }
    }

    s"$label - unblock when permit is released" in ticked { implicit ticker =>
      val p =
        for {
          sem <- sc(1)
          ref <- IO.ref(false)
          _ <- sem.permit.surround { IO.sleep(1.second) >> ref.set(true) }.start
          _ <- IO.sleep(500.millis)
          _ <- sem.permit.surround(IO.unit)
          v <- ref.get
        } yield v

      p must completeAs(true)
    }

    s"$label - release permit if withPermit errors" in real {
      for {
        sem <- sc(1)
        _ <- sem.permit.surround(IO.raiseError(new Exception)).attempt
        res <- sem.permit.surround(IO.unit).mustEqual(())
      } yield res
    }

    s"$label - release permit if action gets canceled" in ticked { implicit ticker =>
      val p =
        for {
          sem <- sc(1)
          fiber <- sem.permit.surround(IO.never).start
          _ <- IO.sleep(1.second)
          _ <- fiber.cancel
          _ <- sem.permit.surround(IO.unit)
        } yield ()

      p must completeAs(())
    }

    s"$label - allow cancelation if blocked waiting for permit" in ticked { implicit ticker =>
      val p = for {
        sem <- sc(0)
        ref <- IO.ref(false)
        f <- sem.permit.surround(IO.unit).onCancel(ref.set(true)).start
        _ <- IO.sleep(1.second)
        _ <- f.cancel
        v <- ref.get
      } yield v

      p must completeAs(true)
    }

    s"$label - not release permit when an acquire gets canceled" in ticked { implicit ticker =>
      val p = for {
        sem <- sc(0)
        _ <- sem.permit.surround(IO.unit).timeout(1.second).attempt
        _ <- sem.permit.surround(IO.unit)
      } yield ()

      p must nonTerminate
    }

    s"$label - acquire n synchronously" in real {
      val n = 20
      val op = sc(20).flatMap { s =>
        (0 until n).toList.traverse(_ => s.acquire).void *> s.available
      }

      op.mustEqual(0L)
    }

    def withLock[T](n: Long, s: Semaphore[IO], check: IO[T]): IO[(Long, T)] =
      s.acquireN(n).background.surround {
        //w/o cs.shift this hangs for coreJS
        s.count.iterateUntil(_ < 0).flatMap(t => check.tupleLeft(t))
      }

    s"$label - available with no available permits" in real {
      val n = 20L
      val op = sc(n)
        .flatMap { s =>
          for {
            _ <- s.acquire.replicateA(n.toInt)
            res <- withLock(1, s, s.available)
          } yield res

        }

      op.mustEqual(-1L -> 0L)
    }

    s"$label - tryAcquire with available permits" in real {
      val n = 20
      val op = sc(30).flatMap { s =>
        for {
          _ <- (0 until n).toList.traverse(_ => s.acquire).void
          t <- s.tryAcquire
        } yield t
      }

      op.mustEqual(true)
    }

    s"$label - tryAcquire with no available permits" in real {
      val n = 20
      val op = sc(20).flatMap { s =>
        for {
          _ <- (0 until n).toList.traverse(_ => s.acquire).void
          t <- s.tryAcquire
        } yield t
      }

      op.mustEqual(false)
    }

    s"$label - tryAcquireN all available permits" in real {
      val n = 20
      val op = sc(20).flatMap { s =>
        for {
          t <- s.tryAcquireN(n.toLong)
        } yield t
      }

      op.mustEqual(true)
    }

    s"$label - offsetting acquires/releases - acquires parallel with releases" in real {
      val permits: Vector[Long] = Vector(1, 0, 20, 4, 0, 5, 2, 1, 1, 3)
      val op = sc(0)
        .flatMap { s =>
          (
            permits.traverse(s.acquireN).void,
            permits.reverse.traverse(s.releaseN).void
          ).parTupled *> s.count
        }

      op.mustEqual(0L)
    }

    s"$label - offsetting acquires/releases - individual acquires/increment in parallel" in real {
      val permits: Vector[Long] = Vector(1, 0, 20, 4, 0, 5, 2, 1, 1, 3)
      val op = sc(0)
        .flatMap { s =>
          (
           permits.parTraverse(s.acquireN).void,
           permits.reverse.parTraverse(s.releaseN).void
          ).parTupled *> s.count
        }

      op.mustEqual(0L)
    }

    s"$label - available with available permits" in real {
      val op = sc(20).flatMap { s =>
        for {
          _ <- s.acquireN(19)
          t <- s.available
        } yield t
      }

      op.mustEqual(1L)
    }

    s"$label - available with 0 available permits" in real {
      val op = sc(20).flatMap { s =>
        for {
          _ <- s.acquireN(20).void
          t <- IO.cede *> s.available
        } yield t
      }

      op.mustEqual(0L)
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

      op.flatMap { case (available, count) =>
        IO(available mustEqual count)
      }
    }

    s"$label - count with no available permits" in real {
      val n: Long = 8
      val op =
        sc(n).flatMap { s =>
          s.acquireN(n) >>
          s.acquireN(n).background.use { _ =>
            s.count.iterateUntil(_ < 0)
          }
       }

      op.mustEqual(-n)
    }

    s"$label - count with 0 available permits" in real {
      val op = sc(20).flatMap { s =>
        s.acquireN(20) >> s.count
      }

      op.mustEqual(0L)
    }
  }
}
