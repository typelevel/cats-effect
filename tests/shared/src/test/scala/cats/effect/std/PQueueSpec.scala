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

/*
 * These tests have been inspired by and adapted from `monix-catnap`'s `ConcurrentQueueSuite`, available at
 * https://github.com/monix/monix/blob/series/3.x/monix-catnap/shared/src/test/scala/monix/catnap/ConcurrentQueueSuite.scala.
 */

package cats.effect
package std

import cats.Order
import cats.arrow.FunctionK
import cats.implicits._

import org.scalacheck.Arbitrary.arbitrary
import org.specs2.specification.core.Fragments

import scala.collection.immutable.{Queue => ScalaQueue}
import scala.concurrent.duration._

class BoundedPQueueSpec extends BaseSpec with PQueueTests {

  override def executionTimeout = 20.seconds

  implicit val orderForInt: Order[Int] = Order.fromLessThan((x, y) => x < y)

  "PQueue" should {
    boundedPQueueTests(PQueue.bounded)
  }

  "PQueue mapK" should {
    boundedPQueueTests(PQueue.bounded[IO, Int](_).map(_.mapK(FunctionK.id)))
  }

  private def boundedPQueueTests(constructor: Int => IO[PQueue[IO, Int]]): Fragments = {
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

      def producer(q: PQueue[IO, Int], n: Int): IO[Unit] =
        if (n > 0) q.offer(count - n).flatMap(_ => producer(q, n - 1))
        else IO.unit

      def consumer(
          q: PQueue[IO, Int],
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

    negativeCapacityConstructionTests(constructor)
    tryOfferOnFullTests(constructor, _.offer(_), _.tryOffer(_), false)
    cancelableOfferTests(constructor, _.offer(_), _.take, _.tryTake)
    cancelableOfferBoundedTests(constructor, _.offer(_), _.take, _.tryTakeN(_))
    cancelableTakeTests(constructor, _.offer(_), _.take)
    tryOfferTryTakeTests(constructor, _.tryOffer(_), _.tryTake)
    commonTests(constructor, _.offer(_), _.tryOffer(_), _.take, _.tryTake, _.size)
    dequeueInPriorityOrder(constructor)
    batchTakeTests(constructor, _.offer(_), _.tryTakeN(_))
    batchOfferTests(constructor, _.tryOfferN(_), _.tryTakeN(_))
  }
}

class UnboundedPQueueSpec extends BaseSpec with PQueueTests {
  sequential

  override def executionTimeout = 20.seconds

  implicit val orderForInt: Order[Int] = Order.fromLessThan((x, y) => x < y)

  "UnboundedPQueue" should {
    unboundedPQueueTests(PQueue.unbounded)
  }

  "UnboundedPQueue mapK" should {
    unboundedPQueueTests(PQueue.unbounded[IO, Int].map(_.mapK(FunctionK.id)))
  }

  private def unboundedPQueueTests(constructor: IO[PQueue[IO, Int]]): Fragments = {
    tryOfferOnFullTests(_ => constructor, _.offer(_), _.tryOffer(_), true)
    tryOfferTryTakeTests(_ => constructor, _.tryOffer(_), _.tryTake)
    commonTests(_ => constructor, _.offer(_), _.tryOffer(_), _.take, _.tryTake, _.size)
    dequeueInPriorityOrder(_ => constructor)
    batchTakeTests(_ => constructor, _.offer(_), _.tryTakeN(_))
    batchOfferTests(_ => constructor, _.tryOfferN(_), _.tryTakeN(_))
  }
}

trait PQueueTests extends QueueTests[PQueue] { self: BaseSpec =>

  def dequeueInPriorityOrder(constructor: Int => IO[PQueue[IO, Int]]): Fragments = {

    /**
     * Hand-rolled scalacheck effect as we don't have that for CE3 yet
     */
    "should dequeue in priority order" in realProp(arbitrary[List[Int]]) { in =>
      for {
        q <- constructor(Int.MaxValue)
        _ <- in.traverse_(q.offer(_))
        out <- List.fill(in.length)(q.take).sequence
        res <- IO(out must beEqualTo(in.sorted))
      } yield res
    }
  }

}
