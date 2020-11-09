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

/*
 * These tests have been inspired by and adapted from `monix-catnap`'s `ConcurrentQueueSuite`, available at
 * https://github.com/monix/monix/blob/series/3.x/monix-catnap/shared/src/test/scala/monix/catnap/ConcurrentQueueSuite.scala.
 */

package cats.effect
package std

import cats.arrow.FunctionK
import org.specs2.specification.core.Fragments

import scala.collection.immutable.{Queue => ScalaQueue}

class BoundedDequeueSpec extends BaseSpec with QueueTests {
  sequential

  "BoundedDequeue" should {
    boundedDequeueTests(
      "BoundedDequeue",
      Dequeue.bounded(_),
      (q: Dequeue[IO, Int], i: Int) => q.offerBack(i),
      (q: Dequeue[IO, Int]) => q.takeFront)
    boundedDequeueTests(
      "BoundedDequeue - reverse",
      Dequeue.bounded(_),
      (q: Dequeue[IO, Int], i: Int) => q.offerFront(i),
      (q: Dequeue[IO, Int]) => q.takeBack)
    boundedDequeueTests(
      "BoundedDequeue mapK",
      Dequeue.bounded[IO, Int](_).map(_.mapK(FunctionK.id)),
      (q: Dequeue[IO, Int], i: Int) => q.offerBack(i),
      (q: Dequeue[IO, Int]) => q.takeFront
    )
    boundedDequeueTests(
      "BoundedDequeue mapK",
      Dequeue.bounded[IO, Int](_).map(_.mapK(FunctionK.id)),
      (q: Dequeue[IO, Int], i: Int) => q.offerFront(i),
      (q: Dequeue[IO, Int]) => q.takeBack
    )
  }

  private def boundedDequeueTests(
      name: String,
      constructor: Int => IO[Dequeue[IO, Int]],
      offer: (Dequeue[IO, Int], Int) => IO[Unit],
      take: Dequeue[IO, Int] => IO[Int]
  ): Fragments = {
    s"$name - demonstrate offer and take with zero capacity" in real {
      for {
        q <- constructor(0)
        _ <- offer(q, 1).start
        v1 <- take(q)
        f <- take(q).start
        _ <- offer(q, 2)
        v2 <- f.joinAndEmbedNever
        r <- IO((v1 must beEqualTo(1)) and (v2 must beEqualTo(2)))
      } yield r
    }

    s"$name - async take with zero capacity" in realWithRuntime { implicit rt =>
      for {
        q <- constructor(0)
        _ <- offer(q, 1).start
        v1 <- take(q)
        _ <- IO(v1 must beEqualTo(1))
        ff <- IO(take(q).unsafeToFuture()).start
        f <- ff.joinAndEmbedNever
        _ <- IO(f.value must beEqualTo(None))
        _ <- offer(q, 2)
        v2 <- IO.fromFuture(IO.pure(f))
        r <- IO(v2 must beEqualTo(2))
      } yield r
    }

    s"$name - offer/take with zero capacity" in real {
      val count = 1000

      def producer(q: Dequeue[IO, Int], n: Int): IO[Unit] =
        if (n > 0) q.offer(count - n).flatMap(_ => producer(q, n - 1))
        else IO.unit

      def consumer(
          q: Dequeue[IO, Int],
          n: Int,
          acc: ScalaQueue[Int] = ScalaQueue.empty
      ): IO[Long] =
        if (n > 0)
          take(q).flatMap { a => consumer(q, n - 1, acc.enqueue(a)) }
        else
          IO.pure(acc.foldLeft(0L)(_ + _))

      for {
        q <- constructor(0)
        p <- producer(q, count).start
        c <- consumer(q, count).start
        _ <- p.join
        v <- c.joinAndEmbedNever
        r <- IO(v must beEqualTo(count.toLong * (count - 1) / 2))
      } yield r
    }

    negativeCapacityConstructionTests(name, constructor)
    tryOfferOnFullTests(name, constructor, false)
    cancelableOfferTests(name, constructor)
    tryOfferTryTakeTests(name, constructor)
    commonTests(name, constructor)
  }
}

class UnboundedDequeueSpec extends BaseSpec with QueueTests {
  sequential

  "UnboundedDequeue" should {
    unboundedDequeueTests("UnboundedDequeue", Dequeue.unbounded)

    unboundedDequeueTests(
      "UnboundedDequeue mapK",
      Dequeue.unbounded[IO, Int].map(_.mapK(FunctionK.id)))
  }

  private def unboundedDequeueTests(
      name: String,
      constructor: IO[Dequeue[IO, Int]]): Fragments = {
    tryOfferOnFullTests(name, _ => constructor, true)
    tryOfferTryTakeTests(name, _ => constructor)
    commonTests(name, _ => constructor)
  }
}
