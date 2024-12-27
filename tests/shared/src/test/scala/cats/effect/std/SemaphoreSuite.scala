/*
 * Copyright 2020-2025 Typelevel
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

import scala.concurrent.duration._

class SemaphoreSuite extends BaseSuite { outer =>

  tests("Semaphore", n => Semaphore[IO](n))
  tests("Semaphore with dual constructors", n => Semaphore.in[IO, IO](n))
  tests("MapK'd semaphore", n => Semaphore[IO](n).map(_.mapK[IO](FunctionK.id)))

  def tests(name: String, sc: Long => IO[Semaphore[IO]]) = {
    real(s"$name throw on negative n") {
      val op = IO(sc(-42))

      op.mustFailWith[IllegalArgumentException]
    }

    ticked(s"$name block if no permits available") { implicit ticker =>
      assertNonTerminate(sc(0).flatMap { sem => sem.permit.surround(IO.unit) })
    }

    real(s"$name execute action if permit is available for it") {
      sc(1).flatMap { sem => sem.permit.surround(IO.unit).mustEqual(()) }
    }

    real(s"$name tryPermit returns true if permit is available for it") {
      sc(1).flatMap { sem => sem.tryPermit.use(IO.pure).mustEqual(true) }
    }

    real(s"$name tryPermit returns false if permit is not available for it") {
      sc(0).flatMap { sem => sem.tryPermit.use(IO.pure).mustEqual(false) }
    }

    ticked(s"$name unblock when permit is released") { implicit ticker =>
      val p =
        for {
          sem <- sc(1)
          ref <- IO.ref(false)
          _ <- sem.permit.surround { IO.sleep(1.second) >> ref.set(true) }.start
          _ <- IO.sleep(500.millis)
          _ <- sem.permit.surround(IO.unit)
          v <- ref.get
        } yield v

      assertCompleteAs(p, true)
    }

    real(s"$name release permit if permit errors") {
      for {
        sem <- sc(1)
        _ <- sem.permit.surround(IO.raiseError(new Exception)).attempt
        res <- sem.permit.surround(IO.unit).mustEqual(())
      } yield res
    }

    real(s"$name release permit if tryPermit errors") {
      for {
        sem <- sc(1)
        _ <- sem.tryPermit.surround(IO.raiseError(new Exception)).attempt
        res <- sem.permit.surround(IO.unit).mustEqual(())
      } yield res
    }

    real(s"$name release permit if permit completes") {
      for {
        sem <- sc(1)
        _ <- sem.permit.surround(IO.unit)
        res <- sem.permit.surround(IO.unit).mustEqual(())
      } yield res
    }

    real(s"$name release permit if tryPermit completes") {
      for {
        sem <- sc(1)
        _ <- sem.tryPermit.surround(IO.unit)
        res <- sem.permit.surround(IO.unit).mustEqual(())
      } yield res
    }

    ticked(s"$name not release permit if tryPermit completes without acquiring a permit") {
      implicit ticker =>
        val p = for {
          sem <- sc(0)
          _ <- sem.tryPermit.surround(IO.unit)
          res <- sem.permit.surround(IO.unit)
        } yield res

        assertNonTerminate(p)
    }

    ticked(s"$name release permit if action gets canceled") { implicit ticker =>
      val p =
        for {
          sem <- sc(1)
          fiber <- sem.permit.surround(IO.never).start
          _ <- IO.sleep(1.second)
          _ <- fiber.cancel
          _ <- sem.permit.surround(IO.unit)
        } yield ()

      assertCompleteAs(p, ())
    }

    ticked(s"$name release tryPermit if action gets canceled") { implicit ticker =>
      val p =
        for {
          sem <- sc(1)
          fiber <- sem.tryPermit.surround(IO.never).start
          _ <- IO.sleep(1.second)
          _ <- fiber.cancel
          _ <- sem.permit.surround(IO.unit)
        } yield ()

      assertCompleteAs(p, ())
    }

    ticked(s"$name allow cancelation if blocked waiting for permit") { implicit ticker =>
      val p = for {
        sem <- sc(0)
        ref <- IO.ref(false)
        f <- sem.permit.surround(IO.unit).onCancel(ref.set(true)).start
        _ <- IO.sleep(1.second)
        _ <- f.cancel
        v <- ref.get
      } yield v

      assertCompleteAs(p, true)
    }

    ticked(s"$name not release permit when an acquire gets canceled") { implicit ticker =>
      val p = for {
        sem <- sc(0)
        _ <- sem.permit.surround(IO.unit).timeout(1.second).attempt
        _ <- sem.permit.surround(IO.unit)
      } yield ()

      assertNonTerminate(p)
    }

    real(s"$name acquire n synchronously") {
      val n = 20
      val op = sc(20).flatMap { s =>
        (0 until n).toList.traverse(_ => s.acquire).void *> s.available
      }

      op.mustEqual(0L)
    }

    ticked(s"$name acquireN does not leak permits upon cancelation") { implicit ticker =>
      val op = Semaphore[IO](1L).flatMap { s =>
        // acquireN(2L) gets one permit, then blocks waiting for another one
        // upon timeout, if it leaked and didn't release the permit it had,
        // the second acquire would block forever
        s.acquireN(2L).timeout(1.second).attempt *> s.acquire
      }

      assertCompleteAs(op, ())
    }

    def withLock[T](n: Long, s: Semaphore[IO], check: IO[T]): IO[(Long, T)] =
      s.acquireN(n).background.surround {
        // w/o cs.shift this hangs for coreJS
        s.count // .flatTap(x => IO.println(s"count is $x"))
          .iterateUntil(_ < 0)
          .flatMap(t => check.tupleLeft(t))
      }

    real(s"$name available with no available permits") {
      val n = 20L
      val op = sc(n).flatMap { s =>
        for {
          _ <- s.acquire.replicateA(n.toInt)
          res <- withLock(1, s, s.available)
        } yield res

      }

      op.mustEqual(-1L -> 0L)
    }

    real(s"$name tryAcquire with available permits") {
      val n = 20
      val op = sc(30).flatMap { s =>
        for {
          _ <- (0 until n).toList.traverse(_ => s.acquire).void
          t <- s.tryAcquire
        } yield t
      }

      op.mustEqual(true)
    }

    real(s"$name tryAcquire with no available permits") {
      val n = 20
      val op = sc(20).flatMap { s =>
        for {
          _ <- (0 until n).toList.traverse(_ => s.acquire).void
          t <- s.tryAcquire
        } yield t
      }

      op.mustEqual(false)
    }

    real(s"$name tryAcquireN all available permits") {
      val n = 20
      val op = sc(20).flatMap { s =>
        for {
          t <- s.tryAcquireN(n.toLong)
        } yield t
      }

      op.mustEqual(true)
    }

    real(s"$name offsetting acquires/releases - acquires parallel with releases") {
      val permits: Vector[Long] = Vector(1, 0, 20, 4, 0, 5, 2, 1, 1, 3)
      val op = sc(0).flatMap { s =>
        (
          permits.traverse(s.acquireN).void,
          permits.reverse.traverse(s.releaseN).void
        ).parTupled *> s.count
      }

      op.mustEqual(0L)
    }

    real(s"$name offsetting acquires/releases - individual acquires/increment in parallel") {
      val permits: Vector[Long] = Vector(1, 0, 20, 4, 0, 5, 2, 1, 1, 3)
      val op = sc(0).flatMap { s =>
        (
          permits.parTraverse(n => s.acquireN(n)),
          permits.reverse.parTraverse(n => s.releaseN(n))
        ).parTupled *> s.count
      }

      op.mustEqual(0L)
    }

    real(s"$name available with available permits") {
      val op = sc(20).flatMap { s =>
        for {
          _ <- s.acquireN(19)
          t <- s.available
        } yield t
      }

      op.mustEqual(1L)
    }

    real(s"$name available with 0 available permits") {
      val op = sc(20).flatMap { s =>
        for {
          _ <- s.acquireN(20).void
          t <- IO.cede *> s.available
        } yield t
      }

      op.mustEqual(0L)
    }

    real(s"$name count with available permits") {
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
          IO(assertEquals(available, count))
      }
    }

    real(s"$name count with no available permits") {
      val n: Long = 8
      val op =
        sc(n).flatMap { s =>
          s.acquireN(n) >>
            s.acquireN(n).background.use { _ => s.count.iterateUntil(_ < 0) }
        }

      op.mustEqual(-n)
    }

    real(s"$name count with 0 available permits") {
      val op = sc(20).flatMap { s => s.acquireN(20) >> s.count }

      op.mustEqual(0L)
    }
  }
}
