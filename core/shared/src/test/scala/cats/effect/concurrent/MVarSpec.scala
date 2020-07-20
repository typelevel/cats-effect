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

package cats.effect
package concurrent

import cats.effect.kernel.Outcome._

import scala.concurrent.duration._

class MVarSpec extends BaseSpec {

  def init[A](a: A): IO[MVar[IO, A]] =
    MVar[IO].of(a)

  "put is cancelable" in real {
    val op = for {
      mVar <- init(0)
      _ <- mVar.put(1).start
      p2 <- mVar.put(2).start
      _ <- mVar.put(3).start
      _ <- IO.sleep(10.millis) // Give put callbacks a chance to register
      _ <- p2.cancel
      _ <- mVar.take
      r1 <- mVar.take
      r3 <- mVar.take
    } yield Set(r1, r3)

    op.flatMap { res =>
      IO {
        res mustEqual Set(1, 3)
      }
    }
  }

  "take is cancelable" in real {
    val op = for {
      mVar <- MVar[IO].empty[Int]
      t1 <- mVar.take.start
      t2 <- mVar.take.start
      t3 <- mVar.take.start
      _ <- IO.sleep(10.millis) // Give take callbacks a chance to register
      _ <- t2.cancel
      _ <- mVar.put(1)
      _ <- mVar.put(3)
      r1 <- t1.join
      r3 <- t3.join
    } yield (r1, r3)

    op.flatMap {
      case (Completed(res1), Completed(res2)) =>
        for {
          x <- res1
          y <- res2
        } yield Set(x, y) mustEqual Set(1, 3)
      case x => fail(x)
    }
  }

  "read is cancelable" in real {
    val op = for {
      mVar <- MVar[IO].empty[Int]
      finished <- Deferred[IO, Int]
      fiber <- mVar.read.flatMap(finished.complete).start
      _ <- IO.sleep(10.millis) // Give read callback a chance to register
      _ <- fiber.cancel
      _ <- mVar.put(10)
      fallback = IO.sleep(100.millis) *> IO.pure(0)
      v <- IO.race(finished.get, fallback)
    } yield v

    op.flatMap { res =>
      IO {
        res must beRight(0)
      }
    }
  }

  "empty; put; take; put; take" in real {
    val op = for {
      av <- MVar[IO].empty[Int]
      isE1 <- av.isEmpty
      _ <- av.put(10)
      isE2 <- av.isEmpty
      r1 <- av.take
      _ <- av.put(20)
      r2 <- av.take
    } yield (isE1, isE2, r1, r2)

    op.flatMap { res =>
      IO {
        res mustEqual ((true, false, 10, 20))
      }
    }
  }

  "empty; tryPut; tryPut; tryTake; tryTake; put; take" in real {
    val op = for {
      av <- MVar[IO].empty[Int]
      isE1 <- av.isEmpty
      p1 <- av.tryPut(10)
      p2 <- av.tryPut(11)
      isE2 <- av.isEmpty
      r1 <- av.tryTake
      r2 <- av.tryTake
      _ <- av.put(20)
      r3 <- av.take
    } yield (isE1, p1, p2, isE2, r1, r2, r3)

    op.flatMap { res =>
      IO {
        res mustEqual ((true, true, false, false, Some(10), None, 20))
      }
    }
  }

  "empty; take; put; take; put" in real {
    val op = for {
      av <- MVar[IO].empty[Int]
      f1 <- av.take.start
      _ <- av.put(10)
      f2 <- av.take.start
      _ <- av.put(20)
      r1 <- f1.join
      r2 <- f2.join
    } yield (r1, r2)

    op.flatMap {
      case (Completed(res1), Completed(res2)) =>
        for {
          x <- res1
          y <- res2
        } yield Set(x, y) mustEqual Set(10, 20)
      case x => fail(x)
    }
  }

  "empty; put; put; put; take; take; take" in real {
    val op = for {
      av <- MVar[IO].empty[Int]
      f1 <- av.put(10).start
      f2 <- av.put(20).start
      f3 <- av.put(30).start
      r1 <- av.take
      r2 <- av.take
      r3 <- av.take
      _ <- f1.join
      _ <- f2.join
      _ <- f3.join
    } yield Set(r1, r2, r3)

    op.flatMap { res =>
      IO {
        res mustEqual Set(10, 20, 30)
      }
    }
  }

  "empty; take; take; take; put; put; put" in real {
    val op = for {
      av <- MVar[IO].empty[Int]
      f1 <- av.take.start
      f2 <- av.take.start
      f3 <- av.take.start
      _ <- av.put(10)
      _ <- av.put(20)
      _ <- av.put(30)
      r1 <- f1.join
      r2 <- f2.join
      r3 <- f3.join
    } yield (r1, r2, r3)

    op.flatMap {
      case (Completed(res1), Completed(res2), Completed(res3)) =>
        for {
          x <- res1
          y <- res2
          z <- res3
          r <- IO(Set(x, y, z) mustEqual Set(10, 20, 30))
        } yield r
      case x => fail(x)
    }
  }

  "initial; take; put; take" in real {
    val op = for {
      av <- init(10)
      isE <- av.isEmpty
      r1 <- av.take
      _ <- av.put(20)
      r2 <- av.take
    } yield (isE, r1, r2)

    op.flatMap { res =>
      IO {
        res mustEqual ((false, 10, 20))
      }
    }
  }

  "initial; read; take" in real {
    val op = for {
      av <- init(10)
      read <- av.read
      take <- av.take
    } yield read + take

    op.flatMap { res =>
      IO {
        res mustEqual 20
      }
    }
  }

  "empty; read; put" in real {
    val op = for {
      av <- MVar[IO].empty[Int]
      read <- av.read.start
      _ <- av.put(10)
      r <- read.join
    } yield r

    op.flatMap {
      case Completed(res) =>
        res.flatMap { r =>
          IO {
            r mustEqual 10
          }
        }
      case x => fail(x)
    }
  }

  "empty; tryRead; read; put; tryRead; read" in real {
    val op = for {
      av <- MVar[IO].empty[Int]
      tryReadEmpty <- av.tryRead
      read <- av.read.start
      _ <- av.put(10)
      tryReadContains <- av.tryRead
      r <- read.join
    } yield (tryReadEmpty, tryReadContains, r)

    op.flatMap {
      case (None, Some(10), Completed(res)) =>
        res.flatMap { r =>
          IO {
            r mustEqual 10
          }
        }
      case x => fail(x)
    }
  }

  "empty; put; swap; read" in real {
    val op = for {
      mVar <- MVar[IO].empty[Int]
      fiber <- mVar.put(10).start
      oldValue <- mVar.swap(20)
      newValue <- mVar.read
      _ <- fiber.join
    } yield (newValue, oldValue)

    op.flatMap { res =>
      IO {
        res mustEqual ((20, 10))
      }
    }
  }

  "put(null) works" in real {
    val op = MVar[IO].empty[String].flatMap { mvar => mvar.put(null) *> mvar.read }

    op.flatMap { res =>
      IO {
        res must beNull
      }
    }
  }

  "producer-consumer parallel loop" in real {
    // Signaling option, because we need to detect completion
    type Channel[A] = MVar[IO, Option[A]]

    def producer(ch: Channel[Int], list: List[Int]): IO[Unit] =
      list match {
        case Nil =>
          ch.put(None) // we are done!
        case head :: tail =>
          // next please
          ch.put(Some(head)).flatMap(_ => producer(ch, tail))
      }

    def consumer(ch: Channel[Int], sum: Long): IO[Long] =
      ch.take.flatMap {
        case Some(x) =>
          // next please
          consumer(ch, sum + x)
        case None =>
          IO.pure(sum) // we are done!
      }

    val count = 1000
    val op = for {
      channel <- init(Option(0))
      // Ensure they run in parallel
      producerFiber <- producer(channel, (0 until count).toList).start
      consumerFiber <- consumer(channel, 0L).start
      _ <- producerFiber.join
      sum <- consumerFiber.join
    } yield sum

    // Evaluate
    op.flatMap {
      case Completed(res) =>
        res.flatMap { r =>
          IO {
            r mustEqual (count.toLong * (count - 1) / 2)
          }
        }
      case x => fail(x)
    }
  }

  //TODO dependent on Parallel instance for IO
  // "stack overflow test" in real {
  //   // Signaling option, because we need to detect completion
  //   type Channel[A] = MVar[IO, Option[A]]
  //   val count = 10000

  //   def consumer(ch: Channel[Int], sum: Long): IO[Long] =
  //     ch.take.flatMap {
  //       case Some(x) =>
  //         // next please
  //         consumer(ch, sum + x)
  //       case None =>
  //         IO.pure(sum) // we are done!
  //     }

  //   def exec(channel: Channel[Int]): IO[Long] = {
  //     val consumerTask = consumer(channel, 0L)
  //     val tasks = for (i <- 0 until count) yield channel.put(Some(i))
  //     val producerTask = tasks.toList.parSequence.flatMap(_ => channel.put(None))

  //     for {
  //       f1 <- producerTask.start
  //       f2 <- consumerTask.start
  //       _ <- f1.join
  //       r <- f2.join
  //     } yield r
  //   }

  //   val op = init(Option(0)).flatMap(exec)

  //   op.flatMap { res =>
  //     IO {
  //       res must beEqualTo(count.toLong * (count - 1) / 2)
  //     }
  //   }
  // }

  "take/put test is stack safe" in real {
    def loop(n: Int, acc: Int)(ch: MVar[IO, Int]): IO[Int] =
      if (n <= 0) IO.pure(acc)
      else
        ch.take.flatMap { x => ch.put(1).flatMap(_ => loop(n - 1, acc + x)(ch)) }

    val count = 1000
    val op = init(1).flatMap(loop(count, 0))

    op.flatMap { res =>
      IO {
        res mustEqual count
      }
    }
  }

  def testStackSequential(channel: MVar[IO, Int]): (Int, IO[Int], IO[Unit]) = {
    val count = 1000

    def readLoop(n: Int, acc: Int): IO[Int] =
      if (n > 0)
        channel.read *>
          channel.take.flatMap(_ => readLoop(n - 1, acc + 1))
      else
        IO.pure(acc)

    def writeLoop(n: Int): IO[Unit] =
      if (n > 0)
        channel.put(1).flatMap(_ => writeLoop(n - 1))
      else
        IO.pure(())

    (count, readLoop(count, 0), writeLoop(count))
  }

  "put is stack safe when repeated sequentially" in real {
    for {
      channel <- MVar[IO].empty[Int]
      (count, reads, writes) = testStackSequential(channel)
      _ <- writes.start
      r <- reads
      results <- IO(r mustEqual count)
    } yield results
  }

  "take is stack safe when repeated sequentially" in real {
    val op = for {
      channel <- MVar[IO].empty[Int]
      (count, reads, writes) = testStackSequential(channel)
      fr <- reads.start
      _ <- writes
      r <- fr.join
    } yield (r, count)

    op.flatMap {
      case (Completed(res), count) =>
        res.flatMap { r =>
          IO {
            r mustEqual count
          }
        }
      case x => fail(x)
    }
  }

  //TODO requires a parallel instance for IO
  // "concurrent take and put"  in real {
  //   val count = if (Platform.isJvm) 10000 else 1000
  //   val op = for {
  //     mVar <- empty[Int]
  //     ref <- Ref[IO].of(0)
  //     takes = (0 until count)
  //       .map(_ => IO.cede *> mVar.read.map2(mVar.take)(_ + _).flatMap(x => ref.update(_ + x)))
  //       .toList
  //       .parSequence
  //     puts = (0 until count).map(_ => IO.cede *> mVar.put(1)).toList.parSequence
  //     fiber1 <- takes.start
  //     fiber2 <- puts.start
  //     _ <- fiber1.join
  //     _ <- fiber2.join
  //     r <- ref.get
  //   } yield r

  //   op.flatMap { res =>
  //     IO {
  //       res must beEqualTo(count * 2)
  //     }
  //   }
  // }
  //

  //There must be a better way to produce const matchError
  //But at least this should print x if we fail to match
  def fail(x: AnyRef) = IO(x must haveClass[Void])
}

sealed trait Void
