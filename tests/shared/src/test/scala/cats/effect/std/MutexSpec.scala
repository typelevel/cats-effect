/*
 * Copyright 2020-2023 Typelevel
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

  final override def executionTimeout = 1.minute

  "ConcurrentMutex" should {
    tests(Mutex.concurrent[IO])
  }

  "AsyncMutex" should {
    tests(Mutex.async[IO])
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

        tsk.replicateA_(if (isJS || isNative) 5 else 5000)
      }

      t.timeoutTo(executionTimeout - 1.second, IO(ko)) mustEqual (())
    }
  }
}
