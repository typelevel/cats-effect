/*
 * Copyright 2020-2022 Typelevel
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

import org.specs2.specification.core.Fragments

import scala.collection.immutable.{Queue => ScalaQueue}
import scala.concurrent.duration._

class BoundedQueueSpec extends BaseSpec with QueueTests[Queue] {

  "BoundedQueue" should {
    // boundedQueueTests("BoundedQueue (concurrent)", Queue.boundedForConcurrent)
    boundedQueueTests("BoundedQueue (async)", Queue.bounded)
    // boundedQueueTests("BoundedQueue mapK", Queue.bounded[IO, Int](_).map(_.mapK(FunctionK.id)))
  }

  private def boundedQueueTests(
      name: String,
      constructor: Int => IO[Queue[IO, Int]]): Fragments = {

    name >> {

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

        val expected = 0.until(fiberCount) flatMap { i =>
          0.until(i).map(_ => i)
        }

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
    }

    negativeCapacityConstructionTests(name, constructor)
    tryOfferOnFullTests(name, constructor, _.offer(_), _.tryOffer(_), false)
    cancelableOfferTests(name, constructor, _.offer(_), _.take, _.tryTake)
    tryOfferTryTakeTests(name, constructor, _.tryOffer(_), _.tryTake)
    commonTests(name, constructor, _.offer(_), _.tryOffer(_), _.take, _.tryTake, _.size)
    batchTakeTests(name, constructor, _.offer(_), _.tryTakeN(_))
    batchOfferTests(name, constructor, _.tryOfferN(_), _.tryTakeN(_))
    boundedBatchOfferTests(name, constructor, _.tryOfferN(_), _.tryTakeN(_))
  }
}

class UnboundedQueueSpec extends BaseSpec with QueueTests[Queue] {
  sequential

  "UnboundedQueue" should {
    unboundedQueueTests("UnboundedQueue", Queue.unbounded)
    unboundedQueueTests(
      "UnboundedQueue mapK",
      Queue.unbounded[IO, Int].map(_.mapK(FunctionK.id)))
  }

  private def unboundedQueueTests(name: String, constructor: IO[Queue[IO, Int]]): Fragments = {
    tryOfferOnFullTests(name, _ => constructor, _.offer(_), _.tryOffer(_), true)
    tryOfferTryTakeTests(name, _ => constructor, _.tryOffer(_), _.tryTake)
    commonTests(name, _ => constructor, _.offer(_), _.tryOffer(_), _.take, _.tryTake, _.size)
    batchTakeTests(name, _ => constructor, _.offer(_), _.tryTakeN(_))
    batchOfferTests(name, _ => constructor, _.tryOfferN(_), _.tryTakeN(_))
  }
}

class DroppingQueueSpec extends BaseSpec with QueueTests[Queue] {
  sequential

  "DroppingQueue" should {
    droppingQueueTests("DroppingQueue", Queue.dropping)
    droppingQueueTests(
      "DroppingQueue mapK",
      Queue.dropping[IO, Int](_).map(_.mapK(FunctionK.id)))
  }

  private def droppingQueueTests(
      name: String,
      constructor: Int => IO[Queue[IO, Int]]
  ): Fragments = {
    negativeCapacityConstructionTests(name, constructor)
    zeroCapacityConstructionTests(name, constructor)
    tryOfferOnFullTests(name, constructor, _.offer(_), _.tryOffer(_), false)
    cancelableOfferTests(name, constructor, _.offer(_), _.take, _.tryTake)
    tryOfferTryTakeTests(name, constructor, _.tryOffer(_), _.tryTake)
    commonTests(name, constructor, _.offer(_), _.tryOffer(_), _.take, _.tryTake, _.size)
    batchTakeTests(name, constructor, _.offer(_), _.tryTakeN(_))
    batchOfferTests(name, constructor, _.tryOfferN(_), _.tryTakeN(_))
  }
}

class CircularBufferQueueSpec extends BaseSpec with QueueTests[Queue] {
  sequential

  "CircularBuffer" should {
    slidingQueueTests("CircularBuffer", Queue.circularBuffer)
    slidingQueueTests(
      "CircularBuffer mapK",
      Queue.circularBuffer[IO, Int](_).map(_.mapK(FunctionK.id)))
  }

  private def slidingQueueTests(
      name: String,
      constructor: Int => IO[Queue[IO, Int]]
  ): Fragments = {
    negativeCapacityConstructionTests(name, constructor)
    zeroCapacityConstructionTests(name, constructor)
    tryOfferOnFullTests(name, constructor, _.offer(_), _.tryOffer(_), true)
    commonTests(name, constructor, _.offer(_), _.tryOffer(_), _.take, _.tryTake, _.size)
    batchTakeTests(name, constructor, _.offer(_), _.tryTakeN(_))
    batchOfferTests(name, constructor, _.tryOfferN(_), _.tryTakeN(_))
  }
}

trait QueueTests[Q[_[_], _]] { self: BaseSpec =>

  def zeroCapacityConstructionTests(
      name: String,
      constructor: Int => IO[Q[IO, Int]]
  ): Fragments = {

    name >> {

      "should raise an exception when constructed with zero capacity" in real {
        val test = IO.defer(constructor(0)).attempt
        test.flatMap { res =>
          IO {
            res must beLike { case Left(e) => e must haveClass[IllegalArgumentException] }
          }
        }
      }
    }
  }

  def negativeCapacityConstructionTests(
      name: String,
      constructor: Int => IO[Q[IO, Int]]
  ): Fragments = {

    name >> {

      "should raise an exception when constructed with a negative capacity" in real {
        val test = IO.defer(constructor(-1)).attempt
        test.flatMap { res =>
          IO {
            res must beLike { case Left(e) => e must haveClass[IllegalArgumentException] }
          }
        }
      }
    }
  }

  def batchOfferTests(
      name: String,
      constructor: Int => IO[Q[IO, Int]],
      tryOfferN: (Q[IO, Int], List[Int]) => IO[List[Int]],
      tryTakeN: (Q[IO, Int], Option[Int]) => IO[Option[List[Int]]],
      transformF: Option[List[Int]] => Option[List[Int]] = identity
  ): Fragments = {
    name >> {
      "should offer all records when there is room" in real {
        for {
          q <- constructor(5)
          offerR <- tryOfferN(q, List(1, 2, 3, 4, 5))
          takeR <- tryTakeN(q, None)
          r <- IO(
            (transformF(takeR) must beEqualTo(Some(List(1, 2, 3, 4, 5)))) and
              (offerR must beEqualTo(List.empty)))
        } yield r
      }
    }
  }

  def boundedBatchOfferTests(
      name: String,
      constructor: Int => IO[Q[IO, Int]],
      tryOfferN: (Q[IO, Int], List[Int]) => IO[List[Int]],
      tryTakeN: (Q[IO, Int], Option[Int]) => IO[Option[List[Int]]],
      transformF: Option[List[Int]] => Option[List[Int]] = identity
  ): Fragments = {
    name >> {
      "should offer some records when the queue is full" in real {
        for {
          q <- constructor(5)
          offerR <- tryOfferN(q, List(1, 2, 3, 4, 5, 6, 7))
          takeR <- tryTakeN(q, None)
          r <- IO(
            (transformF(takeR) must beEqualTo(Some(List(1, 2, 3, 4, 5)))) and
              (offerR must beEqualTo(List(6, 7))))
        } yield r
      }
    }
  }

  def batchTakeTests(
      name: String,
      constructor: Int => IO[Q[IO, Int]],
      offer: (Q[IO, Int], Int) => IO[Unit],
      tryTakeN: (Q[IO, Int], Option[Int]) => IO[Option[List[Int]]],
      transformF: Option[List[Int]] => Option[List[Int]] = identity): Fragments = {
    name >> {
      "should take batches for all records when None is proided" in real {
        for {
          q <- constructor(5)
          _ <- offer(q, 1)
          _ <- offer(q, 2)
          _ <- offer(q, 3)
          _ <- offer(q, 4)
          _ <- offer(q, 5)
          b <- tryTakeN(q, None)
          r <- IO(transformF(b) must beEqualTo(Some(List(1, 2, 3, 4, 5))))
        } yield r
      }
      "should take batches for all records when maxN is proided" in real {
        for {
          q <- constructor(5)
          _ <- offer(q, 1)
          _ <- offer(q, 2)
          _ <- offer(q, 3)
          _ <- offer(q, 4)
          _ <- offer(q, 5)
          b <- tryTakeN(q, Some(5))
          r <- IO(transformF(b) must beEqualTo(Some(List(1, 2, 3, 4, 5))))
        } yield r
      }
      "Should take all records when maxN > queue size" in real {
        for {
          q <- constructor(5)
          _ <- offer(q, 1)
          _ <- offer(q, 2)
          _ <- offer(q, 3)
          _ <- offer(q, 4)
          _ <- offer(q, 5)
          b <- tryTakeN(q, Some(7))
          r <- IO(transformF(b) must beEqualTo(Some(List(1, 2, 3, 4, 5))))
        } yield r
      }
      "Should be empty when queue is empty" in real {
        for {
          q <- constructor(5)
          b <- tryTakeN(q, Some(5))
          r <- IO(transformF(b) must beEqualTo(None))
        } yield r
      }

      "should raise an exception when maxN is not > 0" in real {
        val toAttempt = for {
          q <- constructor(5)
          _ <- tryTakeN(q, Some(-1))
        } yield ()

        val test = IO.defer(toAttempt).attempt
        test.flatMap { res =>
          IO {
            res must beLike { case Left(e) => e must haveClass[IllegalArgumentException] }
          }
        }
      }
    }
  }

  def tryOfferOnFullTests(
      name: String,
      constructor: Int => IO[Q[IO, Int]],
      offer: (Q[IO, Int], Int) => IO[Unit],
      tryOffer: (Q[IO, Int], Int) => IO[Boolean],
      expected: Boolean): Fragments = {

    name >> {

      "should return false on tryOffer when the queue is full" in real {
        for {
          q <- constructor(1)
          _ <- offer(q, 0)
          v <- tryOffer(q, 1)
          r <- IO(v must beEqualTo(expected))
        } yield r
      }
    }
  }

  def offerTakeOverCapacityTests(
      name: String,
      constructor: Int => IO[Q[IO, Int]],
      offer: (Q[IO, Int], Int) => IO[Unit],
      take: Q[IO, Int] => IO[Int]
  ): Fragments = {

    name >> {

      "offer/take over capacity" in real {
        val count = 1000

        def producer(q: Q[IO, Int], n: Int): IO[Unit] =
          if (n > 0) offer(q, count - n).flatMap(_ => producer(q, n - 1))
          else IO.unit

        def consumer(
            q: Q[IO, Int],
            n: Int,
            acc: ScalaQueue[Int] = ScalaQueue.empty
        ): IO[Long] =
          if (n > 0)
            take(q).flatMap { a => consumer(q, n - 1, acc.enqueue(a)) }
          else
            IO.pure(acc.foldLeft(0L)(_ + _))

        for {
          q <- constructor(10)
          p <- producer(q, count).start
          c <- consumer(q, count).start
          _ <- p.join
          v <- c.joinWithNever
          r <- IO(v must beEqualTo(count.toLong * (count - 1) / 2))
        } yield r
      }
    }
  }

  def cancelableOfferTests(
      name: String,
      constructor: Int => IO[Q[IO, Int]],
      offer: (Q[IO, Int], Int) => IO[Unit],
      take: Q[IO, Int] => IO[Int],
      tryTake: Q[IO, Int] => IO[Option[Int]]): Fragments = {

    name >> {

      "demonstrate cancelable offer" in real {
        for {
          q <- constructor(1)
          _ <- offer(q, 1)
          f <- offer(q, 2).start
          _ <- IO.sleep(10.millis)
          _ <- f.cancel
          v1 <- take(q)
          v2 <- tryTake(q)
          r <- IO((v1 must beEqualTo(1)) and (v2 must beEqualTo(None)))
        } yield r
      }
    }
  }

  def tryOfferTryTakeTests(
      name: String,
      constructor: Int => IO[Q[IO, Int]],
      tryOffer: (Q[IO, Int], Int) => IO[Boolean],
      tryTake: Q[IO, Int] => IO[Option[Int]]): Fragments = {

    name >> {

      "tryOffer/tryTake" in real {
        val count = 1000

        def producer(q: Q[IO, Int], n: Int): IO[Unit] =
          if (n > 0) tryOffer(q, count - n).flatMap {
            case true =>
              producer(q, n - 1)
            case false =>
              IO.cede *> producer(q, n)
          }
          else IO.unit

        def consumer(
            q: Q[IO, Int],
            n: Int,
            acc: ScalaQueue[Int] = ScalaQueue.empty
        ): IO[Long] =
          if (n > 0)
            tryTake(q).flatMap {
              case Some(a) => consumer(q, n - 1, acc.enqueue(a))
              case None => IO.cede *> consumer(q, n, acc)
            }
          else
            IO.pure(acc.foldLeft(0L)(_ + _))

        for {
          q <- constructor(10)
          p <- producer(q, count).start
          c <- consumer(q, count).start
          _ <- p.join
          v <- c.joinWithNever
          r <- IO(v must beEqualTo(count.toLong * (count - 1) / 2))
        } yield r
      }
    }
  }

  def commonTests(
      name: String,
      constructor: Int => IO[Q[IO, Int]],
      offer: (Q[IO, Int], Int) => IO[Unit],
      tryOffer: (Q[IO, Int], Int) => IO[Boolean],
      take: Q[IO, Int] => IO[Int],
      tryTake: Q[IO, Int] => IO[Option[Int]],
      size: Q[IO, Int] => IO[Int]): Fragments = {

    name >> {

      "should return the queue size when added to" in real {
        for {
          q <- constructor(1)
          _ <- offer(q, 1)
          _ <- take(q)
          _ <- offer(q, 2)
          sz <- size(q)
          r <- IO(sz must beEqualTo(1))
        } yield r
      }

      "should return None on tryTake when the queue is empty" in real {
        for {
          q <- constructor(1)
          v <- tryTake(q)
          r <- IO(v must beNone)
        } yield r
      }

      "demonstrate sequential offer and take" in real {
        for {
          q <- constructor(1)
          _ <- offer(q, 1)
          v1 <- take(q)
          _ <- offer(q, 2)
          v2 <- take(q)
          r <- IO((v1 must beEqualTo(1)) and (v2 must beEqualTo(2)))
        } yield r
      }

      "demonstrate cancelable take" in real {
        for {
          q <- constructor(1)
          f <- take(q).start
          _ <- IO.sleep(10.millis)
          _ <- f.cancel
          v <- tryOffer(q, 1)
          r <- IO(v must beTrue)
        } yield r
      }

      "async take" in realWithRuntime { implicit rt =>
        for {
          q <- constructor(10)
          _ <- offer(q, 1)
          v1 <- take(q)
          _ <- IO(v1 must beEqualTo(1))
          f <- IO(take(q).unsafeToFuture())
          _ <- IO(f.value must beEqualTo(None))
          _ <- offer(q, 2)
          v2 <- IO.fromFuture(IO.pure(f))
          r <- IO(v2 must beEqualTo(2))
        } yield r
      }
    }
  }
}
