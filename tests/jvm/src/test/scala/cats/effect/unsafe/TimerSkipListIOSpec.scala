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

package cats.effect
package unsafe

import cats.syntax.all._

import scala.concurrent.duration._

import java.util.concurrent.{ConcurrentSkipListSet, ThreadLocalRandom}
import java.util.concurrent.atomic.AtomicLong

class TimerSkipListIOSpec extends BaseSpec {

  final val N = 50000
  final val DELAY = 10000L // ns

  private def drainUntilDone(m: TimerSkipList, done: Ref[IO, Boolean]): IO[Unit] = {
    val pollSome: IO[Long] = IO {
      while ({
        val cb = m.pollFirstIfTriggered(System.nanoTime())
        if (cb ne null) {
          cb(Right(()))
          true
        } else false
      }) {}
      m.peekFirstTriggerTime()
    }
    def go(lastOne: Boolean): IO[Unit] = pollSome.flatMap { next =>
      if (next == Long.MinValue) IO.cede
      else {
        IO.defer {
          val now = System.nanoTime()
          val delay = next - now
          if (delay > 0L) IO.sleep(delay.nanos)
          else IO.unit
        }
      }
    } *> {
      if (lastOne) IO.unit
      else done.get.ifM(go(lastOne = true), IO.cede *> go(lastOne = false))
    }

    go(lastOne = false)
  }

  "TimerSkipList" should {

    "insert/pollFirstIfTriggered concurrently" in real {
      IO.ref(false).flatMap { done =>
        IO { (new TimerSkipList, new AtomicLong) }.flatMap {
          case (m, ctr) =>
            val insert = IO {
              m.insert(
                now = System.nanoTime(),
                delay = DELAY,
                callback = { _ => ctr.getAndIncrement; () },
                tlr = ThreadLocalRandom.current()
              )
            }
            val inserts =
              (insert.parReplicateA_(N) *> IO.sleep(2 * DELAY.nanos)).guarantee(done.set(true))

            val polls = drainUntilDone(m, done).parReplicateA_(2)

            IO.both(inserts, polls).flatMap { _ =>
              IO.sleep(0.5.second) *> IO {
                m.pollFirstIfTriggered(System.nanoTime()) must beNull
                ctr.get() mustEqual N.toLong
              }
            }
        }
      }
    }

    "insert/cancel concurrently" in real {
      IO.ref(false).flatMap { done =>
        IO { (new TimerSkipList, new ConcurrentSkipListSet[Int]) }.flatMap {
          case (m, called) =>
            def insert(id: Int): IO[Runnable] = IO {
              val now = System.nanoTime()
              val canceller = m.insert(
                now = now,
                delay = DELAY,
                callback = { _ => called.add(id); () },
                tlr = ThreadLocalRandom.current()
              )
              canceller
            }

            def cancel(c: Runnable): IO[Unit] = IO {
              c.run()
            }

            val firstBatch = (0 until N).toList
            val secondBatch = (N until (2 * N)).toList

            for {
              // add the first N callbacks:
              cancellers <- firstBatch.traverse(insert)
              // then race removing those, and adding another N:
              _ <- IO.both(
                cancellers.parTraverse(cancel),
                secondBatch.parTraverse(insert)
              )
              // since the fibers calling callbacks
              // are not running yet, the cancelled
              // ones must never be invoked
              _ <- IO.both(
                IO.sleep(2 * DELAY.nanos).guarantee(done.set(true)),
                drainUntilDone(m, done).parReplicateA_(2)
              )
              _ <- IO {
                assert(m.pollFirstIfTriggered(System.nanoTime()) eq null)
                // no cancelled callback should've been called,
                // and all the other ones must've been called:
                val calledIds = {
                  val b = Set.newBuilder[Int]
                  val it = called.iterator()
                  while (it.hasNext()) {
                    b += it.next()
                  }
                  b.result()
                }
                calledIds mustEqual secondBatch.toSet
              }
            } yield ok
        }
      }
    }
  }
}
