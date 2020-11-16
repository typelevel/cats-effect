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

import cats.implicits._
import cats.Order
import cats.arrow.FunctionK
import org.scalacheck.Arbitrary.arbitrary
import org.specs2.specification.core.Fragments

import scala.collection.immutable.{Queue => ScalaQueue}

class BoundedPQueueSpec extends BaseSpec with PQueueTests {

  implicit val orderForInt: Order[Int] = Order.fromLessThan((x, y) => x < y)

  "PQueue" should {
    boundedPQueueTests("PQueue", PQueue.bounded)
    boundedPQueueTests("PQueue mapK", PQueue.bounded[IO, Int](_).map(_.mapK(FunctionK.id)))
  }

  private def boundedPQueueTests(
      name: String,
      constructor: Int => IO[PQueue[IO, Int]]): Fragments = {

    s"$name - demonstrate offer and take with zero capacity" in real {
      for {
        q <- constructor(0)
        _ <- q.offer(1).start
        v1 <- q.take
        f <- q.take.start
        _ <- q.offer(2)
        v2 <- f.joinAndEmbedNever
        r <- IO((v1 must beEqualTo(1)) and (v2 must beEqualTo(2)))
      } yield r
    }

    s"$name - async take with zero capacity" in realWithRuntime { implicit rt =>
      for {
        q <- constructor(0)
        _ <- q.offer(1).start
        v1 <- q.take
        _ <- IO(v1 must beEqualTo(1))
        ff <- IO(q.take.unsafeToFuture()).start
        f <- ff.joinAndEmbedNever
        _ <- IO(f.value must beEqualTo(None))
        _ <- q.offer(2)
        v2 <- IO.fromFuture(IO.pure(f))
        r <- IO(v2 must beEqualTo(2))
      } yield r
    }

    s"$name - offer/take with zero capacity" in real {
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
        v <- c.joinAndEmbedNever
        r <- IO(v must beEqualTo(count.toLong * (count - 1) / 2))
      } yield r
    }

    negativeCapacityConstructionTests(name, constructor)
    tryOfferOnFullTests(name, constructor, _.offer(_), _.tryOffer(_), false)
    cancelableOfferTests(name, constructor, _.offer(_), _.take, _.tryTake)
    tryOfferTryTakeTests(name, constructor, _.tryOffer(_), _.tryTake)
    commonTests(name, constructor, _.offer(_), _.tryOffer(_), _.take, _.tryTake)
    dequeueInPriorityOrder(name, constructor)

  }
}

class UnboundedPQueueSpec extends BaseSpec with PQueueTests {
  sequential

  implicit val orderForInt: Order[Int] = Order.fromLessThan((x, y) => x < y)

  "UnboundedPQueue" should {
    unboundedPQueueTests("UnboundedPQueue", PQueue.unbounded)
    unboundedPQueueTests(
      "UnboundedPQueue mapK",
      PQueue.unbounded[IO, Int].map(_.mapK(FunctionK.id)))
  }

  private def unboundedPQueueTests(
      name: String,
      constructor: IO[PQueue[IO, Int]]): Fragments = {
    tryOfferOnFullTests(name, _ => constructor, _.offer(_), _.tryOffer(_), true)
    tryOfferTryTakeTests(name, _ => constructor, _.tryOffer(_), _.tryTake)
    commonTests(name, _ => constructor, _.offer(_), _.tryOffer(_), _.take, _.tryTake)
    dequeueInPriorityOrder(name, _ => constructor)
  }
}

trait PQueueTests extends QueueTests[PQueue] { self: BaseSpec =>

  def dequeueInPriorityOrder(
      name: String,
      constructor: Int => IO[PQueue[IO, Int]]): Fragments = {

    /**
     * Hand-rolled scalacheck effect as we don't have that for CE3 yet
     */
    s"$name - should dequeue in priority order" in real {
      val gen = arbitrary[List[Int]]
      List.range(1, 100).traverse { _ =>
        val in = gen.sample.get
        for {
          q <- constructor(Int.MaxValue)
          _ <- in.traverse_(q.offer(_))
          out <- List.fill(in.length)(q.take).sequence
          res <- IO(out must beEqualTo(in.sorted))
        } yield res
      }
    }

  }

}
