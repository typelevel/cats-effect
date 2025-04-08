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

import scala.collection.immutable.{Queue => ScalaQueue}

class BoundedPQueueSuite extends BaseSuite with PQueueTests {

  implicit val orderForInt: Order[Int] = Order.fromLessThan((x, y) => x < y)

  boundedPQueueTests("PQueue", PQueue.bounded)
  boundedPQueueTests("PQueue mapK", PQueue.bounded[IO, Int](_).map(_.mapK(FunctionK.id)))

  private def boundedPQueueTests(name: String, constructor: Int => IO[PQueue[IO, Int]]) = {
    real("demonstrate offer and take with zero capacity") {
      for {
        q <- constructor(0)
        _ <- q.offer(1).start
        v1 <- q.take
        f <- q.take.start
        _ <- q.offer(2)
        v2 <- f.joinWithNever
        r <- IO {
          assertEquals(v1, 1)
          assertEquals(v2, 2)
        }
      } yield r
    }

    realWithRuntime("async take with zero capacity") { implicit rt =>
      for {
        q <- constructor(0)
        _ <- q.offer(1).start
        v1 <- q.take
        _ <- IO(assertEquals(v1, 1))
        ff <- IO(q.take.unsafeToFuture()).start
        f <- ff.joinWithNever
        _ <- IO(assertEquals(f.value, None))
        _ <- q.offer(2)
        v2 <- IO.fromFuture(IO.pure(f))
        r <- IO(assertEquals(v2, 2))
      } yield r
    }

    real("offer/take with zero capacity") {
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
        r <- IO(assertEquals(v, count.toLong * (count - 1) / 2))
      } yield r
    }

    negativeCapacityConstructionTests(name, constructor)
    tryOfferOnFullTests(name, constructor, _.offer(_), _.tryOffer(_), false)
    cancelableOfferTests(name, constructor, _.offer(_), _.take, _.tryTake)
    cancelableOfferBoundedTests(name, constructor, _.offer(_), _.take, _.tryTakeN(_))
    cancelableTakeTests(name, constructor, _.offer(_), _.take)
    tryOfferTryTakeTests(name, constructor, _.tryOffer(_), _.tryTake)
    commonTests(name, constructor, _.offer(_), _.tryOffer(_), _.take, _.tryTake, _.size)
    dequeueInPriorityOrder(name, constructor)
    batchTakeTests(name, constructor, _.offer(_), _.tryTakeN(_))
    batchOfferTests(name, constructor, _.tryOfferN(_), _.tryTakeN(_))
  }
}

class UnboundedPQueueSuite extends BaseSuite with PQueueTests {

  implicit val orderForInt: Order[Int] = Order.fromLessThan((x, y) => x < y)

  unboundedPQueueTests("UnboundedPQueue", PQueue.unbounded)
  unboundedPQueueTests(
    "UnboundedPQueue mapK",
    PQueue.unbounded[IO, Int].map(_.mapK(FunctionK.id)))

  private def unboundedPQueueTests(name: String, constructor: IO[PQueue[IO, Int]]) = {
    tryOfferOnFullTests(name, _ => constructor, _.offer(_), _.tryOffer(_), true)
    tryOfferTryTakeTests(name, _ => constructor, _.tryOffer(_), _.tryTake)
    commonTests(name, _ => constructor, _.offer(_), _.tryOffer(_), _.take, _.tryTake, _.size)
    dequeueInPriorityOrder(name, _ => constructor)
    batchTakeTests(name, _ => constructor, _.offer(_), _.tryTakeN(_))
    batchOfferTests(name, _ => constructor, _.tryOfferN(_), _.tryTakeN(_))
  }
}

trait PQueueTests extends QueueTests[PQueue] { self: BaseSuite =>

  def dequeueInPriorityOrder(name: String, constructor: Int => IO[PQueue[IO, Int]]) = {

    /**
     * Hand-rolled scalacheck effect as we don't have that for CE3 yet
     */
    realProp(s"$name - should dequeue in priority order", arbitrary[List[Int]]) { in =>
      for {
        q <- constructor(Int.MaxValue)
        _ <- in.traverse_(q.offer(_))
        out <- List.fill(in.length)(q.take).sequence
        res <- IO(assertEquals(out, in.sorted))
      } yield res
    }
  }

}
