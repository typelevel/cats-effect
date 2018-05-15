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

package cats.effect
package concurrent

import catalysts.Platform
import cats.implicits._
import org.scalatest.{AsyncFunSuite, Matchers}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

class MVarConcurrentTests extends BaseMVarTests {
  def init[A](a: A): IO[MVar[IO, A]] =
    MVar[IO].init(a)

  def initF[A](fa: IO[A]): IO[MVar[IO, A]] =
    MVar[IO].initF(fa)

  def empty[A]: IO[MVar[IO, A]] =
    MVar[IO].empty[A]

  test("put is cancelable") {
    val task = for {
      mVar <- init(0)
      _  <- mVar.put(1).start
      p2 <- mVar.put(2).start
      _  <- mVar.put(3).start
      _  <- p2.cancel
      _  <- mVar.take
      r1 <- mVar.take
      r3 <- mVar.take
    } yield List(r1, r3)

    for (r <- task.unsafeToFuture()) yield {
      r shouldBe List(1, 3)
    }
  }

  test("take is cancelable") {
    val task = for {
      mVar <- empty[Int]
      t1 <- mVar.take.start
      t2 <- mVar.take.start
      t3 <- mVar.take.start
      _  <- t2.cancel
      _  <- mVar.put(1)
      _  <- mVar.put(3)
      r1 <- t1.join
      r3 <- t3.join
    } yield List(r1, r3)

    for (r <- task.unsafeToFuture()) yield {
      r shouldBe List(1, 3)
    }
  }

  test("read is cancelable") {
    val task = for {
      mVar <- MVar[IO].empty[Int]
      finished <- Deferred.async[IO, Int]
      fiber <- mVar.read.flatMap(finished.complete).start
      _ <- fiber.cancel
      _ <- mVar.put(10)
      fallback = IO.sleep(100.millis) *> IO.pure(0)
      v <- IO.race(finished.get, fallback)
    } yield v

    for (r <- task.unsafeToFuture()) yield {
      r shouldBe Right(0)
    }
  }
}

class MVarAsyncTests extends BaseMVarTests {
  def init[A](a: A): IO[MVar[IO, A]] =
    MVar.initAsync(a)

  def initF[A](fa: IO[A]): IO[MVar[IO, A]] =
    MVar.initAsyncF(fa)

  def empty[A]: IO[MVar[IO, A]] =
    MVar.emptyAsync
}

abstract class BaseMVarTests extends AsyncFunSuite with Matchers {
  implicit override def executionContext: ExecutionContext =
    ExecutionContext.Implicits.global
  
  def init[A](a: A): IO[MVar[IO, A]]
  def initF[A](fa: IO[A]): IO[MVar[IO, A]]
  def empty[A]: IO[MVar[IO, A]]

  test("empty; put; take; put; take") {
    val task = for {
      av <- empty[Int]
      _  <- av.put(10)
      r1 <- av.take
      _  <- av.put(20)
      r2 <- av.take
    } yield List(r1,r2)

    for (r <- task.unsafeToFuture()) yield {
      r shouldBe List(10, 20)
    }
  }

  test("empty; take; put; take; put") {
    val task = for {
      av <- empty[Int]
      f1 <- av.take.start
      _  <- av.put(10)
      f2 <- av.take.start
      _  <- av.put(20)
      r1 <- f1.join
      r2 <- f2.join
    } yield List(r1,r2)

    for (r <- task.unsafeToFuture()) yield {
      r shouldBe List(10, 20)
    }
  }

  test("empty; put; put; put; take; take; take") {
    val task = for {
      av <- empty[Int]
      f1 <- av.put(10).start
      f2 <- av.put(20).start
      f3 <- av.put(30).start
      r1 <- av.take
      r2 <- av.take
      r3 <- av.take
      _  <- f1.join
      _  <- f2.join
      _  <- f3.join
    } yield List(r1, r2, r3)

    for (r <- task.unsafeToFuture()) yield {
      r shouldBe List(10, 20, 30)
    }
  }

  test("empty; take; take; take; put; put; put") {
    val task = for {
      av <- empty[Int]
      f1 <- av.take.start
      f2 <- av.take.start
      f3 <- av.take.start
      _  <- av.put(10)
      _  <- av.put(20)
      _  <- av.put(30)
      r1 <- f1.join
      r2 <- f2.join
      r3 <- f3.join
    } yield List(r1, r2, r3)

    for (r <- task.unsafeToFuture()) yield {
      r shouldBe List(10, 20, 30)
    }
  }

  test("initial; take; put; take") {
    val task = for {
      av <- init(10)
      r1 <- av.take
      _  <- av.put(20)
      r2 <- av.take
    } yield List(r1,r2)

    for (v <- task.unsafeToFuture()) yield {
      v shouldBe List(10, 20)
    }
  }


  test("initial; read; take") {
    val task = for {
      av <- init(10)
      read <- av.read
      take <- av.take
    } yield read + take

    for (v <- task.unsafeToFuture()) yield {
      v shouldBe 20
    }
  }

  test("empty; read; put") {
    val task = for {
      av <- empty[Int]
      read <- av.read.start
      _ <- av.put(10)
      r <- read.join
    } yield r

    for (v <- task.unsafeToFuture()) yield {
      v shouldBe 10
    }
  }

  test("put(null) works") {
    val task = empty[String].flatMap { mvar =>
      mvar.put(null) *> mvar.read
    }
    for (v <- task.unsafeToFuture()) yield {
      v shouldBe null
    }
  }

  test("producer-consumer parallel loop") {
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

    val count = 10000
    val sumTask = for {
      channel <- init(Option(0))
      // Ensure they run in parallel
      producerFiber <- (IO.shift *> producer(channel, (0 until count).toList)).start
      consumerFiber <- (IO.shift *> consumer(channel, 0L)).start
      _   <- producerFiber.join
      sum <- consumerFiber.join
    } yield sum

    // Evaluate
    for (r <- sumTask.unsafeToFuture()) yield {
      r shouldBe (count.toLong * (count - 1) / 2)
    }
  }

  test("stack overflow test") {
    // Signaling option, because we need to detect completion
    type Channel[A] = MVar[IO, Option[A]]
    val count = 10000

    def consumer(ch: Channel[Int], sum: Long): IO[Long] =
      ch.take.flatMap {
        case Some(x) =>
          // next please
          consumer(ch, sum + x)
        case None =>
          IO.pure(sum) // we are done!
      }

    def exec(channel: Channel[Int]): IO[Long] = {
      val consumerTask = consumer(channel, 0L)
      val tasks = for (i <- 0 until count) yield channel.put(Some(i))
      val producerTask = tasks.toList.parSequence.flatMap(_ => channel.put(None))

      for {
        f1 <- producerTask.start
        f2 <- consumerTask.start
        _ <- f1.join
        r <- f2.join
      } yield r
    }

    val task = init(Option(0)).flatMap(exec)
    for (r <- task.unsafeToFuture()) yield {
      r shouldBe count.toLong * (count - 1) / 2
    }
  }

  test("take/put test is stack safe") {
    def loop(n: Int, acc: Int)(ch: MVar[IO, Int]): IO[Int] =
      if (n <= 0) IO.pure(acc) else
        ch.take.flatMap { x =>
          ch.put(1).flatMap(_ => loop(n - 1, acc + x)(ch))
        }

    val count = if (Platform.isJvm) 10000 else 5000
    val task = init(1).flatMap(loop(count, 0))

    for (r <- task.unsafeToFuture()) yield {
      r shouldBe count
    }
  }

  def testStackSequential(channel: MVar[IO, Int]): (Int, IO[Int], IO[Unit]) = {
    val count = if (Platform.isJvm) 10000 else 5000

    def readLoop(n: Int, acc: Int): IO[Int] = {
      if (n > 0)
        channel.read *>
          channel.take.flatMap(_ => readLoop(n - 1, acc + 1))
      else
        IO.pure(acc)
    }

    def writeLoop(n: Int): IO[Unit] = {
      if (n > 0)
        channel.put(1).flatMap(_ => writeLoop(n - 1))
      else
        IO.pure(())
    }

    (count, readLoop(count, 0), writeLoop(count))
  }

  test("put is stack safe when repeated sequentially") {
    val task = for {
      channel <- empty[Int]
      (count, reads, writes) = testStackSequential(channel)
      _ <- writes.start
      r <- reads
    } yield r == count

    for (r <- task.unsafeToFuture()) yield {
      r shouldBe true
    }
  }

  test("take is stack safe when repeated sequentially") {
    val task = for {
      channel <- empty[Int]
      (count, reads, writes) = testStackSequential(channel)
      fr <- reads.start
      _ <- writes
      r <- fr.join
    } yield r == count

    for (r <- task.unsafeToFuture()) yield {
      r shouldBe true
    }
  }

  test("initF works") {
    val task = for {
      channel <- initF(IO(10))
      r1 <- channel.read
      r2 <- channel.read
      r3 <- channel.take
    } yield List(r1, r2, r3)

    for (r <- task.unsafeToFuture()) yield {
      r shouldBe List(10, 10, 10)
    }
  }

  test("concurrent take and put") {
    val count = if (Platform.isJvm) 10000 else 1000
    val task = for {
      mvar <- empty[Int]
      ref <- Ref[IO, Int](0)
      takes = (0 until count).toList.map(_ => IO.shift *> mvar.take.flatMap(x => ref.modify(_ + x))).parSequence
      puts = (0 until count).toList.map(_ => IO.shift *> mvar.put(1)).parSequence
      fiber1 <- takes.start
      fiber2 <- puts.start
      _ <- fiber1.join
      _ <- fiber2.join
      r <- ref.get
    } yield r

    for (r <- task.unsafeToFuture()) yield {
      r shouldBe count
    }
  }
}
