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
package std

import cats.implicits._
import cats.arrow.FunctionK
import org.specs2.specification.core.Fragments

import org.scalacheck.Arbitrary, Arbitrary.arbitrary

import scala.collection.immutable.{Queue => ScalaQueue}

class BoundedDequeueSpec extends BaseSpec with DequeueTests {
  sequential

  "BoundedDequeue" should {
    boundedDequeueTests(
      "BoundedDequeue",
      Dequeue.bounded(_),
      _.offerBack(_),
      _.tryOfferBack(_),
      _.takeFront,
      _.tryTakeFront
    )
    boundedDequeueTests(
      "BoundedDequeue - reverse",
      Dequeue.bounded(_),
      _.offerFront(_),
      _.tryOfferFront(_),
      _.takeBack,
      _.tryTakeBack
    )
    boundedDequeueTests(
      "BoundedDequeue mapK",
      Dequeue.bounded[IO, Int](_).map(_.mapK(FunctionK.id)),
      _.offerBack(_),
      _.tryOfferBack(_),
      _.takeFront,
      _.tryTakeFront
    )
    boundedDequeueTests(
      "BoundedDequeue mapK - reverse",
      Dequeue.bounded[IO, Int](_).map(_.mapK(FunctionK.id)),
      _.offerFront(_),
      _.tryOfferFront(_),
      _.takeBack,
      _.tryTakeBack
    )
  }

  private def boundedDequeueTests(
      name: String,
      constructor: Int => IO[Dequeue[IO, Int]],
      offer: (Dequeue[IO, Int], Int) => IO[Unit],
      tryOffer: (Dequeue[IO, Int], Int) => IO[Boolean],
      take: Dequeue[IO, Int] => IO[Int],
      tryTake: Dequeue[IO, Int] => IO[Option[Int]]
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
    tryOfferOnFullTests(name, constructor, offer, tryOffer, false)
    cancelableOfferTests(name, constructor, offer, take, tryTake)
    tryOfferTryTakeTests(name, constructor, tryOffer, tryTake)
    commonTests(name, constructor, offer, tryOffer, take, tryTake)
    reverse(name, constructor)
  }
}

class UnboundedDequeueSpec extends BaseSpec with QueueTests[Dequeue] {
  sequential

  "UnboundedDequeue" should {
    unboundedDequeueTests(
      "UnboundedDequeue",
      Dequeue.unbounded,
      _.offerBack(_),
      _.tryOfferBack(_),
      _.takeFront,
      _.tryTakeFront)

    unboundedDequeueTests(
      "UnboundedDequeue - reverse",
      Dequeue.unbounded,
      _.offerFront(_),
      _.tryOfferFront(_),
      _.takeBack,
      _.tryTakeBack)

    unboundedDequeueTests(
      "UnboundedDequeue mapK",
      Dequeue.unbounded[IO, Int].map(_.mapK(FunctionK.id)),
      _.offerBack(_),
      _.tryOfferBack(_),
      _.takeFront,
      _.tryTakeFront
    )

    unboundedDequeueTests(
      "UnboundedDequeue mapK - reverse",
      Dequeue.unbounded[IO, Int].map(_.mapK(FunctionK.id)),
      _.offerFront(_),
      _.tryOfferFront(_),
      _.takeBack,
      _.tryTakeBack
    )
  }

  private def unboundedDequeueTests(
      name: String,
      constructor: IO[Dequeue[IO, Int]],
      offer: (Dequeue[IO, Int], Int) => IO[Unit],
      tryOffer: (Dequeue[IO, Int], Int) => IO[Boolean],
      take: Dequeue[IO, Int] => IO[Int],
      tryTake: Dequeue[IO, Int] => IO[Option[Int]]
  ): Fragments = {
    tryOfferOnFullTests(name, _ => constructor, offer, tryOffer, true)
    tryOfferTryTakeTests(name, _ => constructor, tryOffer, tryTake)
    commonTests(name, _ => constructor, offer, tryOffer, take, tryTake)
  }
}

trait DequeueTests extends QueueTests[Dequeue] { self: BaseSpec =>

  def reverse(name: String, constructor: Int => IO[Dequeue[IO, Int]]): Fragments = {

    /**
     * Hand-rolled scalacheck effect as we don't have that for CE3 yet
     */
    s"$name - reverse" in real {
      val gen = arbitrary[List[Int]]
      List.range(1, 100).traverse { _ =>
        val in = gen.sample.get
        for {
          q <- constructor(Int.MaxValue)
          _ <- in.traverse_(q.offer(_))
          _ <- q.reverse
          out <- List.fill(in.length)(q.take).sequence
          res <- IO(out must beEqualTo(in.reverse))
        } yield res
      }
    }

  }

}
