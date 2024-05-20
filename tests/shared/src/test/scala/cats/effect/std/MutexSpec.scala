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

final class MutexSpec extends BaseSpec with DetectPlatform {

  final override def executionTimeout = 2.minutes

  "ConcurrentMutex" should {
    tests(Mutex.apply[IO])
  }

  "Mutex with dual constructors" should {
    tests(Mutex.in[IO, IO])
  }

  "MapK'd Mutex" should {
    tests(Mutex[IO].map(_.mapK[IO](FunctionK.id)))
  }

  def tests(mutex: IO[Mutex[IO]]): Fragments = {
    "execute action if free" in real {
      mutex.flatMap { m => m.lock.surround(IO.unit).mustEqual(()) }
    }

    "be reusable" in real {
      mutex.flatMap { m =>
        val p = m.lock.surround(IO.unit)

        (p, p).tupled.mustEqual(((), ()))
      }
    }

    "free on error" in real {
      mutex.flatMap { m =>
        val p =
          m.lock.surround(IO.raiseError(new Exception)).attempt >>
            m.lock.surround(IO.unit)

        p.mustEqual(())
      }
    }

    "block action if not free" in ticked { implicit ticker =>
      mutex.flatMap { m =>
        m.lock.surround(IO.never) >>
          m.lock.surround(IO.unit)
      } must nonTerminate
    }

    "used concurrently" in ticked { implicit ticker =>
      mutex.flatMap { m =>
        val p =
          IO.sleep(1.second) >>
            m.lock.surround(IO.unit)

        (p, p).parTupled
      } must completeAs(((), ()))
    }

    "free on cancellation" in ticked { implicit ticker =>
      val p = for {
        m <- mutex
        f <- m.lock.surround(IO.never).start
        _ <- IO.sleep(1.second)
        _ <- f.cancel
        _ <- m.lock.surround(IO.unit)
      } yield ()

      p must completeAs(())
    }

    "allow cancellation if blocked waiting for lock" in ticked { implicit ticker =>
      val p = for {
        m <- mutex
        ref <- IO.ref(false)
        b <- m.lock.surround(IO.never).start
        _ <- IO.sleep(1.second)
        f <- m.lock.surround(IO.unit).onCancel(ref.set(true)).start
        _ <- IO.sleep(1.second)
        _ <- f.cancel
        _ <- IO.sleep(1.second)
        v <- ref.get
        _ <- b.cancel
      } yield v

      p must completeAs(true)
    }

    "gracefully handle canceled waiters" in ticked { implicit ticker =>
      val p = mutex.flatMap { m =>
        m.lock.surround {
          for {
            f <- m.lock.useForever.start
            _ <- IO.sleep(1.second)
            _ <- f.cancel
          } yield ()
        }
      }
      p must completeAs(())
    }

    "not deadlock when highly contended" in real {
      mutex.flatMap(_.lock.use_.parReplicateA_(10)).replicateA_(10000).as(true)
    }

    "handle cancelled acquire" in real {
      val t = mutex.flatMap { m =>
        val short = m.lock.use { _ => IO.sleep(5.millis) }
        val long = m.lock.use { _ => IO.sleep(20.millis) }
        val tsk = IO.race(IO.race(short, short), IO.race(long, long)).flatMap { _ =>
          // this will hang if a cancelled
          // acquire left the mutex in an
          // invalid state:
          m.lock.use_
        }

        tsk.replicateA_(if (isJVM) 3000 else 5)
      }

      t mustEqual (())
    }

    "handle multiple concurrent cancels during release" in real {
      val t = mutex.flatMap { m =>
        val task = for {
          f1 <- m.lock.allocated
          (_, f1Release) = f1
          f2 <- m.lock.use_.start
          _ <- IO.sleep(5.millis)
          f3 <- m.lock.use_.start
          _ <- IO.sleep(5.millis)
          f4 <- m.lock.use_.start
          _ <- IO.sleep(5.millis)
          _ <- (f1Release, f2.cancel, f3.cancel).parTupled
          _ <- f4.join
        } yield ()

        task.replicateA_(if (isJVM) 1000 else 5)
      }

      t mustEqual (())
    }

    "preserve waiters order (FIFO) on a non-race cancellation" in ticked { implicit ticker =>
      val numbers = List.range(1, 10)
      val p = (mutex, IO.ref(List.empty[Int])).flatMapN {
        case (m, ref) =>
          for {
            f1 <- m.lock.allocated
            (_, f1Release) = f1
            f2 <- m.lock.use_.start
            _ <- IO.sleep(1.millis)
            t <- numbers.parTraverse_ { i =>
              IO.sleep(i.millis) >>
                m.lock.surround(ref.update(acc => i :: acc))
            }.start
            _ <- IO.sleep(100.millis)
            _ <- f2.cancel
            _ <- f1Release
            _ <- t.join
            r <- ref.get
          } yield r.reverse
      }

      p must completeAs(numbers)
    }

    "cancellation must not corrupt Mutex" in ticked { implicit ticker =>
      val p = mutex.flatMap { m =>
        for {
          f1 <- m.lock.allocated
          (_, f1Release) = f1
          f2 <- m.lock.use_.start
          _ <- IO.sleep(1.millis)
          f3 <- m.lock.use_.start
          _ <- IO.sleep(1.millis)
          f4 <- m.lock.use_.start
          _ <- IO.sleep(1.millis)
          _ <- f2.cancel
          _ <- f3.cancel
          _ <- f4.join
          _ <- f1Release
        } yield ()
      }

      p must nonTerminate
    }
  }
}
