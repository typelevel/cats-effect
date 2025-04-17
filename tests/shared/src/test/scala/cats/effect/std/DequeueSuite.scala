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

package cats.effect
package std

import cats.arrow.FunctionK
import cats.implicits._

import org.scalacheck.Arbitrary.arbitrary

import scala.collection.immutable.{Queue => ScalaQueue}
import scala.concurrent.duration._

class BoundedDequeueSuite extends BaseSuite with DequeueTests {

  override def executionTimeout = 20.seconds

  boundedDequeueTests(
    "BoundedDequeue (forward)",
    Dequeue.bounded(_),
    _.offerBack(_),
    _.tryOfferBack(_),
    _.takeFront,
    _.tryTakeFront,
    _.tryTakeFrontN(_),
    _.size
  )

  boundedDequeueTests(
    "BoundedDequeue (reversed)",
    Dequeue.bounded(_),
    _.offerFront(_),
    _.tryOfferFront(_),
    _.takeBack,
    _.tryTakeBack,
    _.tryTakeBackN(_),
    _.size
  )

  boundedDequeueTests(
    "BoundedDequeue mapK (forward)",
    Dequeue.bounded[IO, Int](_).map(_.mapK(FunctionK.id)),
    _.offerBack(_),
    _.tryOfferBack(_),
    _.takeFront,
    _.tryTakeFront,
    _.tryTakeFrontN(_),
    _.size
  )

  boundedDequeueTests(
    "BoundedDequeue mapK (reversed)",
    Dequeue.bounded[IO, Int](_).map(_.mapK(FunctionK.id)),
    _.offerFront(_),
    _.tryOfferFront(_),
    _.takeBack,
    _.tryTakeBack,
    _.tryTakeBackN(_),
    _.size
  )

  private def boundedDequeueTests(
      name: String,
      constructor: Int => IO[Dequeue[IO, Int]],
      offer: (Dequeue[IO, Int], Int) => IO[Unit],
      tryOffer: (Dequeue[IO, Int], Int) => IO[Boolean],
      take: Dequeue[IO, Int] => IO[Int],
      tryTake: Dequeue[IO, Int] => IO[Option[Int]],
      tryTakeN: (Dequeue[IO, Int], Option[Int]) => IO[List[Int]],
      size: Dequeue[IO, Int] => IO[Int]
  ) = {
    real(s"$name - demonstrate offer and take with zero capacity") {
      for {
        q <- constructor(0)
        _ <- offer(q, 1).start
        v1 <- take(q)
        f <- take(q).start
        _ <- offer(q, 2)
        v2 <- f.joinWithNever
        r <- IO {
          assertEquals(v1, 1)
          assertEquals(v2, 2)
        }
      } yield r
    }

    realWithRuntime(s"$name - async take with zero capacity") { implicit rt =>
      for {
        q <- constructor(0)
        _ <- offer(q, 1).start
        v1 <- take(q)
        _ <- IO(assertEquals(v1, 1))
        ff <- IO(take(q).unsafeToFuture()).start
        f <- ff.joinWithNever
        _ <- IO(assertEquals(f.value, None))
        _ <- offer(q, 2)
        v2 <- IO.fromFuture(IO.pure(f))
        r <- IO(assertEquals(v2, 2))
      } yield r
    }

    real(s"$name - offer/take with zero capacity") {
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
        v <- c.joinWithNever
        r <- IO(assertEquals(v, count.toLong * (count - 1) / 2))
      } yield r
    }

    negativeCapacityConstructionTests(name, constructor)
    tryOfferOnFullTests(name, constructor, offer, tryOffer, false)
    cancelableOfferTests(name, constructor, offer, take, tryTake)
    cancelableOfferBoundedTests(name, constructor, offer, take, tryTakeN)
    cancelableTakeTests(name, constructor, offer, take)
    tryOfferTryTakeTests(name, constructor, tryOffer, tryTake)
    commonTests(name, constructor, offer, tryOffer, take, tryTake, size)
    batchTakeTests(name, constructor, _.offer(_), _.tryTakeFrontN(_))
    batchTakeTests(name, constructor, _.offer(_), _.tryTakeBackN(_), _.reverse)
    batchOfferTests(name, constructor, _.tryOfferBackN(_), _.tryTakeFrontN(_))
    batchOfferTests(name, constructor, _.tryOfferFrontN(_), _.tryTakeFrontN(_))
    batchOfferTests(name, constructor, _.tryOfferBackN(_), _.tryTakeBackN(_), _.reverse)
    batchOfferTests(name, constructor, _.tryOfferFrontN(_), _.tryTakeBackN(_), _.reverse)
    boundedBatchOfferTests(name, constructor, _.tryOfferBackN(_), _.tryTakeBackN(_), _.reverse)
    boundedBatchOfferTests(name, constructor, _.tryOfferFrontN(_), _.tryTakeBackN(_), _.reverse)
    reverse(name, constructor)
  }
}

class UnboundedDequeueSuite extends BaseSuite with QueueTests[Dequeue] {

  unboundedDequeueTests(
    "UnboundedDequeue (forward)",
    Dequeue.unbounded,
    _.offerBack(_),
    _.tryOfferBack(_),
    _.takeFront,
    _.tryTakeFront,
    _.size)

  unboundedDequeueTests(
    "UnboundedDequeue (reversed)",
    Dequeue.unbounded,
    _.offerFront(_),
    _.tryOfferFront(_),
    _.takeBack,
    _.tryTakeBack,
    _.size)

  unboundedDequeueTests(
    "UnboundedDequeue mapK (forward)",
    Dequeue.unbounded[IO, Int].map(_.mapK(FunctionK.id)),
    _.offerBack(_),
    _.tryOfferBack(_),
    _.takeFront,
    _.tryTakeFront,
    _.size
  )

  unboundedDequeueTests(
    "UnboundedDequeue mapK (reversed)",
    Dequeue.unbounded[IO, Int].map(_.mapK(FunctionK.id)),
    _.offerFront(_),
    _.tryOfferFront(_),
    _.takeBack,
    _.tryTakeBack,
    _.size
  )

  private def unboundedDequeueTests(
      name: String,
      constructor: IO[Dequeue[IO, Int]],
      offer: (Dequeue[IO, Int], Int) => IO[Unit],
      tryOffer: (Dequeue[IO, Int], Int) => IO[Boolean],
      take: Dequeue[IO, Int] => IO[Int],
      tryTake: Dequeue[IO, Int] => IO[Option[Int]],
      size: Dequeue[IO, Int] => IO[Int]) = {
    tryOfferOnFullTests(name, _ => constructor, offer, tryOffer, true)
    tryOfferTryTakeTests(name, _ => constructor, tryOffer, tryTake)
    commonTests(name, _ => constructor, offer, tryOffer, take, tryTake, size)
    batchTakeTests(name, _ => constructor, _.offer(_), _.tryTakeFrontN(_))
    batchTakeTests(name, _ => constructor, _.offer(_), _.tryTakeBackN(_), _.reverse)
    batchOfferTests(name, _ => constructor, _.tryOfferBackN(_), _.tryTakeFrontN(_))
    batchOfferTests(name, _ => constructor, _.tryOfferFrontN(_), _.tryTakeFrontN(_))
    batchOfferTests(name, _ => constructor, _.tryOfferBackN(_), _.tryTakeBackN(_), _.reverse)
    batchOfferTests(name, _ => constructor, _.tryOfferFrontN(_), _.tryTakeBackN(_), _.reverse)
  }
}

trait DequeueTests extends QueueTests[Dequeue] { self: BaseSuite =>

  def reverse(name: String, constructor: Int => IO[Dequeue[IO, Int]]) = {

    /**
     * Hand-rolled scalacheck effect as we don't have that for CE3 yet
     */
    realProp(s"$name - reverse", arbitrary[List[Int]]) { in =>
      for {
        q <- constructor(Int.MaxValue)
        _ <- in.traverse_(q.offer(_))
        _ <- q.reverse
        out <- List.fill(in.length)(q.take).sequence
        res <- IO(assertEquals(out, in.reverse))
      } yield res

    }

  }

}
