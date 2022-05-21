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
    boundedQueueTests(Queue.bounded)
  }

  "BoundedQueue mapK" should {
    boundedQueueTests(Queue.bounded[IO, Int](_).map(_.mapK(FunctionK.id)))
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

    negativeCapacityConstructionTests(constructor)
    tryOfferOnFullTests(constructor, _.offer(_), _.tryOffer(_), false)
    cancelableOfferTests(constructor, _.offer(_), _.take, _.tryTake)
    cancelableTakeTests(constructor, _.offer(_), _.take, _.tryTakeN(_))
    tryOfferTryTakeTests(constructor, _.tryOffer(_), _.tryTake)
    commonTests(constructor, _.offer(_), _.tryOffer(_), _.take, _.tryTake, _.size)
    batchTakeTests(constructor, _.offer(_), _.tryTakeN(_))
    batchOfferTests(constructor, _.tryOfferN(_), _.tryTakeN(_))
    boundedBatchOfferTests(constructor, _.tryOfferN(_), _.tryTakeN(_))
  }
}

class UnboundedQueueSpec extends BaseSpec with QueueTests[Queue] {
  sequential

  "UnboundedQueue" should {
    unboundedQueueTests(Queue.unbounded)
  }

  "UnboundedQueue mapk" should {
    unboundedQueueTests(Queue.unbounded[IO, Int].map(_.mapK(FunctionK.id)))
  }

  private def unboundedQueueTests(constructor: IO[Queue[IO, Int]]): Fragments = {
    tryOfferOnFullTests(_ => constructor, _.offer(_), _.tryOffer(_), true)
    tryOfferTryTakeTests(_ => constructor, _.tryOffer(_), _.tryTake)
    commonTests(_ => constructor, _.offer(_), _.tryOffer(_), _.take, _.tryTake, _.size)
    batchTakeTests(_ => constructor, _.offer(_), _.tryTakeN(_))
    batchOfferTests(_ => constructor, _.tryOfferN(_), _.tryTakeN(_))
  }
}

class DroppingQueueSpec extends BaseSpec with QueueTests[Queue] {
  sequential

  "DroppingQueue" should {
    droppingQueueTests(Queue.dropping)
  }

  "DroppingQueue mapK" should {
    droppingQueueTests(Queue.dropping[IO, Int](_).map(_.mapK(FunctionK.id)))
  }

  private def droppingQueueTests(constructor: Int => IO[Queue[IO, Int]]): Fragments = {
    negativeCapacityConstructionTests(constructor)
    zeroCapacityConstructionTests(constructor)
    tryOfferOnFullTests(constructor, _.offer(_), _.tryOffer(_), false)
    cancelableOfferTests(constructor, _.offer(_), _.take, _.tryTake)
    cancelableTakeTests(constructor, _.offer(_), _.take, _.tryTakeN(_))
    tryOfferTryTakeTests(constructor, _.tryOffer(_), _.tryTake)
    commonTests(constructor, _.offer(_), _.tryOffer(_), _.take, _.tryTake, _.size)
    batchTakeTests(constructor, _.offer(_), _.tryTakeN(_))
    batchOfferTests(constructor, _.tryOfferN(_), _.tryTakeN(_))
  }
}

class CircularBufferQueueSpec extends BaseSpec with QueueTests[Queue] {
  sequential

  "CircularBuffer" should {
    slidingQueueTests(Queue.circularBuffer)
  }

  "CircularBuffer mapK" should {
    slidingQueueTests(Queue.circularBuffer[IO, Int](_).map(_.mapK(FunctionK.id)))
  }

  private def slidingQueueTests(constructor: Int => IO[Queue[IO, Int]]): Fragments = {
    negativeCapacityConstructionTests(constructor)
    zeroCapacityConstructionTests(constructor)
    tryOfferOnFullTests(constructor, _.offer(_), _.tryOffer(_), true)
    commonTests(constructor, _.offer(_), _.tryOffer(_), _.take, _.tryTake, _.size)
    batchTakeTests(constructor, _.offer(_), _.tryTakeN(_))
    batchOfferTests(constructor, _.tryOfferN(_), _.tryTakeN(_))
  }
}

trait QueueTests[Q[_[_], _]] { self: BaseSpec =>

  def zeroCapacityConstructionTests(constructor: Int => IO[Q[IO, Int]]): Fragments = {
    "should raise an exception when constructed with zero capacity" in real {
      val test = IO.defer(constructor(0)).attempt
      test.flatMap { res =>
        IO {
          res must beLike { case Left(e) => e must haveClass[IllegalArgumentException] }
        }
      }
    }
  }

  def negativeCapacityConstructionTests(constructor: Int => IO[Q[IO, Int]]): Fragments = {
    "should raise an exception when constructed with a negative capacity" in real {
      val test = IO.defer(constructor(-1)).attempt
      test.flatMap { res =>
        IO {
          res must beLike { case Left(e) => e must haveClass[IllegalArgumentException] }
        }
      }
    }
  }

  def batchOfferTests(
      constructor: Int => IO[Q[IO, Int]],
      tryOfferN: (Q[IO, Int], List[Int]) => IO[List[Int]],
      tryTakeN: (Q[IO, Int], Option[Int]) => IO[List[Int]],
      transform: List[Int] => List[Int] = identity
  ): Fragments = {
    "should offer all records when there is room" in real {
      for {
        q <- constructor(5)
        offerR <- tryOfferN(q, List(1, 2, 3, 4, 5))
        takeR <- tryTakeN(q, None)
        r <- IO(
          (transform(takeR) must beEqualTo(List(1, 2, 3, 4, 5))) and
            (offerR must beEqualTo(List.empty)))
      } yield r
    }
  }

  def boundedBatchOfferTests(
      constructor: Int => IO[Q[IO, Int]],
      tryOfferN: (Q[IO, Int], List[Int]) => IO[List[Int]],
      tryTakeN: (Q[IO, Int], Option[Int]) => IO[List[Int]],
      transform: List[Int] => List[Int] = identity
  ): Fragments = {
    "should offer some records when the queue is full" in real {
      for {
        q <- constructor(5)
        offerR <- tryOfferN(q, List(1, 2, 3, 4, 5, 6, 7))
        takeR <- tryTakeN(q, None)
        r <- IO(
          (transform(takeR) must beEqualTo(List(1, 2, 3, 4, 5))) and
            (offerR must beEqualTo(List(6, 7))))
      } yield r
    }
  }

  def batchTakeTests(
      constructor: Int => IO[Q[IO, Int]],
      offer: (Q[IO, Int], Int) => IO[Unit],
      tryTakeN: (Q[IO, Int], Option[Int]) => IO[List[Int]],
      transform: List[Int] => List[Int] = identity): Fragments = {

    "should take batches for all records when None is provided" in real {
      for {
        q <- constructor(5)
        _ <- offer(q, 1)
        _ <- offer(q, 2)
        _ <- offer(q, 3)
        _ <- offer(q, 4)
        _ <- offer(q, 5)
        b <- tryTakeN(q, None)
        r <- IO(transform(b) must beEqualTo(List(1, 2, 3, 4, 5)))
      } yield r
    }
    "should take batches for all records when maxN is provided" in real {
      for {
        q <- constructor(5)
        _ <- offer(q, 1)
        _ <- offer(q, 2)
        _ <- offer(q, 3)
        _ <- offer(q, 4)
        _ <- offer(q, 5)
        b <- tryTakeN(q, Some(5))
        r <- IO(transform(b) must beEqualTo(List(1, 2, 3, 4, 5)))
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
        r <- IO(transform(b) must beEqualTo(List(1, 2, 3, 4, 5)))
      } yield r
    }
    "Should be empty when queue is empty" in real {
      for {
        q <- constructor(5)
        b <- tryTakeN(q, Some(5))
        r <- IO(transform(b) must beEqualTo(List.empty))
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

  def tryOfferOnFullTests(
      constructor: Int => IO[Q[IO, Int]],
      offer: (Q[IO, Int], Int) => IO[Unit],
      tryOffer: (Q[IO, Int], Int) => IO[Boolean],
      expected: Boolean): Fragments = {

    "should return false on tryOffer when the queue is full" in real {
      for {
        q <- constructor(1)
        _ <- offer(q, 0)
        v <- tryOffer(q, 1)
        r <- IO(v must beEqualTo(expected))
      } yield r
    }
  }

  def offerTakeOverCapacityTests(
      constructor: Int => IO[Q[IO, Int]],
      offer: (Q[IO, Int], Int) => IO[Unit],
      take: Q[IO, Int] => IO[Int]
  ): Fragments = {

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

  def cancelableOfferTests(
      constructor: Int => IO[Q[IO, Int]],
      offer: (Q[IO, Int], Int) => IO[Unit],
      take: Q[IO, Int] => IO[Int],
      tryTake: Q[IO, Int] => IO[Option[Int]]): Fragments = {

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

  def cancelableTakeTests(
      constructor: Int => IO[Q[IO, Int]],
      offer: (Q[IO, Int], Int) => IO[Unit],
      take: Q[IO, Int] => IO[Int],
      tryTakeN: (Q[IO, Int], Option[Int]) => IO[List[Int]]): Fragments = {

    "not lose data on canceled take" in real {
      // this is a race condition test so we iterate
      val test = 0.until(10000).toList traverse_ { _ =>
        for {
          q <- constructor(100)
          _ <- 0.until(100).toList.traverse_(offer(q, _))

          latch <- IO.deferred[Unit]
          backR <- IO.ref(Vector[Int]())

          fiber <- {
            val action = for {
              // take half of the queue's contents
              front <- 0.until(50).toVector.traverse(_ => take(q))
              _ <- backR.set(front)

              // release the canceler to race with us
              _ <- latch.complete(())

              // take the other half of the contents with atomic writing
              _ <- 50.until(100).toVector traverse { _ =>
                IO uncancelable { poll =>
                  // if data is lost, it would need to manifest here
                  // specifically, take would claim a value but flatMap wouldn't run
                  poll(take(q)).flatMap(a => backR.update(_ :+ a))
                }
              }
            } yield ()

            action.start
          }

          _ <- latch.get
          _ <- fiber.cancel

          // grab whatever is left in the queue
          remainder <- tryTakeN(q, None)
          _ <- backR.update(_ ++ remainder.toVector)

          // if we lost data, we'll be missing a value in backR
          results <- backR.get
          _ <- IO(results must contain(0.until(100)))
        } yield ()
      }

      test.as(ok)

    }
  }

  def tryOfferTryTakeTests(
      constructor: Int => IO[Q[IO, Int]],
      tryOffer: (Q[IO, Int], Int) => IO[Boolean],
      tryTake: Q[IO, Int] => IO[Option[Int]]): Fragments = {

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

  def commonTests(
      constructor: Int => IO[Q[IO, Int]],
      offer: (Q[IO, Int], Int) => IO[Unit],
      tryOffer: (Q[IO, Int], Int) => IO[Boolean],
      take: Q[IO, Int] => IO[Int],
      tryTake: Q[IO, Int] => IO[Option[Int]],
      size: Q[IO, Int] => IO[Int]): Fragments = {

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
