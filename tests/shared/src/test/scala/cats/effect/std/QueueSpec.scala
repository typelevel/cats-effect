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

/*
 * These tests have been inspired by and adapted from `monix-catnap`'s `ConcurrentQueueSuite`, available at
 * https://github.com/monix/monix/blob/series/3.x/monix-catnap/shared/src/test/scala/monix/catnap/ConcurrentQueueSuite.scala.
 */

package cats.effect
package std

import cats.arrow.FunctionK
import cats.syntax.all._

import org.specs2.execute.Result
import org.specs2.specification.core.Fragments

import scala.collection.immutable.{Queue => ScalaQueue}
import scala.concurrent.duration._

class BoundedQueueSpec extends BaseSpec with QueueTests[Queue] with DetectPlatform {

  "BoundedQueue (concurrent)" should {
    boundedQueueTests(i => if (i == 0) Queue.synchronous else Queue.boundedForConcurrent(i))
  }

  "BoundedQueue (async)" should {
    boundedQueueTests(Queue.bounded)
  }

  "BoundedQueue constructor" should {
    "not OOM" in real {
      Queue.bounded[IO, Unit](Int.MaxValue).as(true)
    }
  }

  "BoundedQueue mapK" should {
    boundedQueueTests(Queue.bounded[IO, Int](_).map(_.mapK(FunctionK.id)))
  }

  "synchronous queue" should {
    "respect fifo order" in ticked { implicit ticker =>
      val test = for {
        q <- Queue.synchronous[IO, Int]

        _ <- 0.until(5).toList traverse_ { i =>
          val f = for {
            _ <- IO.sleep(i.second)
            _ <- q.offer(i)
          } yield ()

          f.start
        }

        _ <- IO.sleep(5.seconds)
        result <- q.take.replicateA(5)
      } yield result

      test must completeAs(0.until(5).toList)
    }

    "not lose takers when offerer is canceled and there are no other takers" in real {
      val test = for {
        q <- Queue.synchronous[IO, Unit]

        latch1 <- IO.deferred[Unit]
        offerer <- IO.uncancelable(p => latch1.complete(()) >> p(q.offer(()))).start
        _ <- latch1.get

        // take and cancel the offerer at the same time
        // the race condition we're going for is *simultaneous*
        // failing to repeat the race is likely, in which case the
        // offerer will be canceled before we finish registering the take
        taker <- IO.both(q.take, offerer.cancel).start

        // if we failed the race condition above, this offer will unblock the take
        // if we succeeded in replicating the race, this offer will have no taker
        // with the bug, this will timeout since both will block
        // with the bug fix, either the join will return immediately, or it will
        // be unblocked by the offer
        _ <- IO.race(taker.joinWithNever, q.offer(()).delayBy(500.millis))
      } yield ()

      test.parReplicateA(if (isJS || isNative) 1 else 1000).as(ok)
    }
  }

  private def boundedQueueTests(constructor: Int => IO[Queue[IO, Int]]): Fragments = {
    "demonstrate offer and take with zero capacity" in real {
      for {
        q <- constructor(0)
        _ <- q.offer(1).start
        v1 <- q.take
        f <- q.take.start
        _ <- q.offer(2)
        v2 <- f.joinWithNever
        r <- IO((v1 must beEqualTo(1)) and (v2 must beEqualTo(2)))
      } yield r
    }

    "respect fifo order with zero capacity" in ticked { implicit ticker =>
      val test = for {
        q <- constructor(0)

        _ <- 0.until(5).toList traverse { i => (IO.sleep(i.millis) *> q.offer(i)).start }

        _ <- IO.sleep(10.millis)

        results <- q.take.replicateA(5)
      } yield results

      test must completeAs(List(0, 1, 2, 3, 4))
    }

    "demonstrate cancelable offer with zero capacity" in ticked { implicit ticker =>
      val test1 = for {
        q <- constructor(0)

        offerer1 <- (IO.sleep(1.millis) *> q.offer(0)).start
        offerer2 <- (IO.sleep(2.millis) *> q.offer(1)).start
        _ <- IO.sleep(10.millis)

        _ <- offerer1.cancel

        result <- q.take
        outcome <- offerer2.join
      } yield (result, outcome.isSuccess)

      test1 must completeAs((1, true))

      val test2 = for {
        q <- constructor(0)

        offerer1 <- (IO.sleep(1.millis) *> q.offer(0)).start
        offerer2 <- (IO.sleep(2.millis) *> q.offer(1)).start
        _ <- IO.sleep(10.millis)

        _ <- offerer2.cancel

        result <- q.take
        outcome <- offerer1.join
      } yield (result, outcome.isSuccess)

      test2 must completeAs((0, true))
    }

    "demonstrate cancelable take with zero capacity" in ticked { implicit ticker =>
      val test1 = for {
        q <- constructor(0)

        taker1 <- (IO.sleep(1.millis) *> q.take).start
        taker2 <- (IO.sleep(2.millis) *> q.take).start
        _ <- IO.sleep(10.millis)

        _ <- taker1.cancel

        _ <- q.offer(42)
        result <- taker2.joinWithNever
      } yield result

      test1 must completeAs(42)

      val test2 = for {
        q <- constructor(0)

        taker1 <- (IO.sleep(1.millis) *> q.take).start
        taker2 <- (IO.sleep(2.millis) *> q.take).start
        _ <- IO.sleep(10.millis)

        _ <- taker1.cancel

        _ <- q.offer(42)
        result <- taker2.joinWithNever
      } yield result

      test2 must completeAs(42)
    }

    "async take with zero capacity" in realWithRuntime { implicit rt =>
      for {
        q <- constructor(0)
        _ <- q.offer(1).start
        v1 <- q.take
        _ <- IO(v1 must beEqualTo(1))
        ff <- IO(q.take.unsafeToFuture()).start
        f <- ff.joinWithNever
        _ <- IO(f.value must beEqualTo(None))
        _ <- q.offer(2)
        v2 <- IO.fromFuture(IO.pure(f))
        r <- IO(v2 must beEqualTo(2))
      } yield r
    }

    "offer/take with zero capacity" in real {
      val count = 1000

      def producer(q: Queue[IO, Int], n: Int): IO[Unit] =
        if (n > 0) q.offer(count - n).flatMap(_ => producer(q, n - 1))
        else IO.unit

      def consumer(
          q: Queue[IO, Int],
          n: Int,
          acc: ScalaQueue[Int] = ScalaQueue.empty
      ): IO[Long] =
        if (n > 0)
          q.take.flatMap { a => consumer(q, n - 1, acc.enqueue(a)) }
        else
          IO.pure(acc.foldLeft(0L)(_ + _))

      for {
        q <- constructor(0)
        p <- producer(q, count).start
        c <- consumer(q, count).start
        _ <- p.join
        v <- c.joinWithNever
        r <- IO(v must beEqualTo(count.toLong * (count - 1) / 2))
      } yield r
    }

    "offer/take from many fibers simultaneously" in real {
      val fiberCount = 50

      val expected = 0.until(fiberCount) flatMap { i => 0.until(i).map(_ => i) }

      def producer(q: Queue[IO, Int], id: Int): IO[Unit] =
        q.offer(id).replicateA_(id)

      def consumer(q: Queue[IO, Int], num: Int): IO[List[Int]] =
        q.take.replicateA(num)

      for {
        q <- constructor(64)

        produce = 0.until(fiberCount).toList.parTraverse_(producer(q, _))
        consume = 0.until(fiberCount).toList.parTraverse(consumer(q, _)).map(_.flatten)

        results <- produce &> consume

        _ <- IO(results must containTheSameElementsAs(expected))
      } yield ok
    }

    "offer/take at high contention" in real {
      val size = if (isJS || isNative) 10000 else 100000

      val action = constructor(size) flatMap { q =>
        def par(action: IO[Unit], num: Int): IO[Unit] =
          if (num <= 10)
            action
          else
            par(action, num / 2) &> par(action, num / 2)

        val offerers = par(q.offer(0), size / 2)
        val takers = par(q.take.void, size / 2)

        offerers &> takers
      }

      action.as(ok)
    }

    "offer/take with a single consumer and high contention" in real {
      constructor(8) flatMap { q =>
        val offerer = List.fil