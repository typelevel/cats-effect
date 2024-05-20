/*
 * Copyright 2020-2024 Typelevel
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

import org.specs2.specification.core.Fragments

import scala.concurrent.duration._

class SemaphoreSpec extends BaseSpec { outer =>

  "Semaphore" should {
    tests(n => Semaphore[IO](n))
  }

  "Semaphore with dual constructors" should {
    tests(n => Semaphore.in[IO, IO](n))
  }

  "MapK'd semaphore" should {
    tests(n => Semaphore[IO](n).map(_.mapK[IO](FunctionK.id)))
  }

  def tests(sc: Long => IO[Semaphore[IO]]): Fragments = {
    "throw on negative n" in real {
      val op = IO(sc(-42))

      op.mustFailWith[IllegalArgumentException]
    }

    "block if no permits available" in ticked { implicit ticker =>
      sc(0).flatMap { sem => sem.permit.surround(IO.unit) } must nonTerminate
    }

    "execute action if permit is available for it" in real {
      sc(1).flatMap { sem => sem.permit.surround(IO.unit).mustEqual(()) }
    }

    "tryPermit returns true if permit is available for it" in real {
      sc(1).flatMap { sem => sem.tryPermit.use(IO.pure).mustEqual(true) }
    }

    "tryPermit returns false if permit is not available for it" in real {
      sc(0).flatMap { sem => sem.tryPermit.use(IO.pure).mustEqual(false) }
    }

    "unblock when permit is released" in ticked { implicit ticker =>
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

    "release permit if permit errors" in real {
      for {
        sem <- sc(1)
        _ <- sem.permit.surround(IO.raiseError(new Exception)).attempt
        res <- sem.permit.surround(IO.unit).mustEqual(())
      } yield res
    }

    "release permit if tryPermit errors" in real {
      for {
        sem <- sc(1)
        _ <- sem.tryPermit.surround(IO.raiseError(new Exception)).attempt
        res <- sem.permit.surround(IO.unit).mustEqual(())
      } yield res
    }

    "release permit if permit completes" in real {
      for {
        sem <- sc(1)
        _ <- sem.permit.surround(IO.unit)
        res <- sem.permit.surround(IO.unit).mustEqual(())
      } yield res
    }

    "release permit if tryPermit completes" in real {
      for {
        sem <- sc(1)
        _ <- sem.tryPermit.surround(IO.unit)
        res <- sem.permit.surround(IO.unit).mustEqual(())
      } yield res
    }

    "not release permit if tryPermit completes without acquiring a permit" in ticked {
      implicit ticker =>
        val p = for {
          sem <- sc(0)
          _ <- sem.tryPermit.surround(IO.unit)
          res <- sem.permit.surround(IO.unit)
        } yield res

        p must nonTerminate
    }

    "release permit if action gets canceled" in ticked { implicit ticker =>
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

    "release tryPermit if action gets canceled" in ticked { implicit ticker =>
      val p =
        for {
          sem <- sc(1)
          fiber <- sem.tryPermit.surround(IO.never).start
          _ <- IO.sleep(1.second)
          _ <- fiber.cancel
          _ <- sem.permit.surround(IO.unit)
        } yield ()

      p must completeAs(())
    }

    "allow cancelation if blocked waiting for permit" in ticked { implicit ticker =>
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

    "not release permit when an acquire gets canceled" in ticked { implicit ticker =>
      val p = for {
        sem <- sc(0)
        _ <- sem.permit.surround(IO.unit).timeout(1.second).attempt
        _ <- sem.permit.surround(IO.unit)
      } yield ()

      p must nonTerminate
    }

    "acquire n synchronously" in real {
      val n = 20
      val op = sc(20).flatMap { s =>
        (0 until n).toList.traverse(_ => s.acquire).void *> s.available
      }

      op.mustEqual(0L)
    }

    "acquireN does not leak permits upon cancelation" in ticked { implicit ticker =>
      val op = Semaphore[IO](1L).flatMap { s =>
        // acquireN(2L) gets one permit, then blocks waiting for another one
        // upon timeout, if it leaked and didn't release the permit it had,
        // the second acquire would block forever
        s.acquireN(2L).timeout(1.second).attempt *> s.acquire
      }

      op must completeAs(())
    }

    def withLock[T](n: Long, s: Semaphore[IO], check: IO[T]): IO[(Long, T)] =
      s.acquireN(n).background.surround {
        // w/o cs.shift this hangs for coreJS
        s.count // .flatTap(x => IO.println(s"count is $x"))
          .iterateUntil(_ < 0)
          .flatMap(t => check.tupleLeft(t))
      }

    "available with no available permits" in real {
      val n = 20L
      val op = sc(n).flatMap { s =>
        for {
          _ <- s.acquire.replicateA(n.toInt)
          res <- withLock(1, s, s.available)
        } yield res

      }

      op.mustEqual(-1L -> 0L)
    }

    "tryAcquire with available permits" in real {
      val n = 20
      val op = sc(30).flatMap { s =>
        for {
          _ <- (0 until n).toList.traverse(_ => s.acquire).void
          t <- s.tryAcquire
        } yield t
      }

      op.mustEqual(true)
    }

    "tryAcquire with no available permits" in real {
      val n = 20
      val op = sc(20).flatMap { s =>
        for {
          _ <- (0 until n).toList.traverse(_ => s.acquire).void
          t <- s.tryAcquire
        } yield t
      }

      op.mustEqual(false)
    }

    "tryAcquireN all available permits" in real {
      val n = 20
      val op = sc(20).flatMap { s =>
        for {
          t <- s.tryAcquireN(n.toLong)
        } yield t
      }

      op.mustEqual(true)
    }

    "offsetting acquires/releases - acquires parallel with releases" in real {
      val permits: Vector[Long] = Vector(1, 0, 20, 4, 0, 5, 2, 1, 1, 3)
      val op = sc(0).flatMap { s =>
        (
          permits.traverse(s.acquireN).void,
          permits.reverse.traverse(s.releaseN).void
        ).parTupled *> s.count
      }

      op.mustEqual(0L)
    }

    "offsetting acquires/releases - individual acquires/increment in parallel" in real {
      val permits: Vector[Long] = Vector(1, 0, 20, 4, 0, 5, 2, 1, 1, 3)
      val op = sc(0).flatMap { s =>
        (
          permits.parTraverse(n => s.acquireN(n)),
          permits.reverse.parTraverse(n => s.releaseN(n))
        ).parTupled *> s.count
      }

      op.mustEqual(0L)
    }

    "available with available permits" in real {
      val op = sc(20).flatMap { s =>
        for {
          _ <- s.acquireN(19)
          t <- s.available
        } yield t
      }

      op.mustEqual(1L)
    }

    "available with 0 available permits" in real {
      val op = sc(20).flatMap { s =>
        for {
          _ <- s.acquireN(20).void
          t <- IO.cede *> s.available
        } yield t
      }

      op.mustEqual(0L)
    }

    "count with available permits" in real {
      val n = 18
      val op = sc(20).flatMap { s =>
        for {
          _ <- (0 until n).toList.traverse(_ => s.acquire).void
          a <- s.available
          t <- s.count
        } yield (a, t)
      }

      op.flatMap {
        case (available, count) =>
          IO(available mustEqual count)
      }
    }

    "count with no available permits" in real {
      val n: Long = 8
      val op =
        sc(n).flatMap { s =>
          s.acquireN(n) >>
            s.acquireN(n).background.use { _ => s.count.iterateUntil(_ < 0) }
        }

      op.mustEqual(-n)
    }

    "count with 0 available permits" in real {
      val op = sc(20).flatMap { s => s.acquireN(20) >> s.count }

      op.mustEqual(0L)
    }
  }
}
