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

import org.specs2.execute.Result
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
    cancelableTakeTests(constructor, _.offer(_), _.take)
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
    cancelableTakeTests(constructor, _.offer(_), _.take)
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

    "ensure offerers are awakened under all circumstances" in real {
      val test = for {
        // _ <- IO.println(s"$prefix >> iterating...")
        q <- constructor(5)
        offeredR <- IO.deferred[Boolean]

        // fill the queue
        _ <- 0.until(5).toVector.traverse_(offer(q, _))

        // start two offerers and race them against each other. one populates offeredR
        offerer1 = offer(q, 42) guaranteeCase {
          case Outcome.Succeeded(_) =>
            offeredR.complete(true).void

          case _ =>
            offeredR.complete(false).void
        }

        offer1 <- offerer1.start
        offer2 <- offer(q, 24) /*.guarantee(IO.println(s"$prefix >> take2 finished"))*/ .start

        // wait for state to quiesce
        _ <- IO.sleep(250.millis)
        // _ <- IO.println(s"$prefix >> waking")
        // race the dequeue against canceling the ref-populating offerer
        _ <- IO.both(take(q), offer1.cancel)

        // detect the race condition
        offered <- offeredR.get
        // _ <- IO.println(s"$prefix >> received $taken")

        // what we're testing here is that *one* of the two ran
        _ <-
          if (offered)
            // if offer1 was resumed, we couldn't reproduce the race condition
            offer2.cancel
          else
            // if neither offerer resumed, then we'll be false from offer1 and offer2.join will hang
            offer2.join
      } yield ()

      test.parReplicateA(16).as(ok)
    }
  }

  def cancelableTakeTests(
      constructor: Int => IO[Q[IO, Int]],
      offer: (Q[IO, Int], Int) => IO[Unit],
      take: Q[IO, Int] => IO[Int]): Fragments = {

    "not lose data on canceled take" in real {
      val test = for {
        q <- constructor(100)

        _ <- 0.until(100).toList.traverse_(offer(q, _) *> IO.cede).start

        results <- IO.ref(-1)
        latch <- IO.deferred[Unit]

        consumer = for {
          _ <- latch.complete(())
          _ <- 0.until(100).toList traverse_ { _ =>
            IO uncancelable { poll => poll(take(q)).flatMap(results.set(_)) }
          }
        } yield ()

        consumerFiber <- consumer.start

        _ <- latch.get
        _ <- consumerFiber.cancel

        max <- results.get
        continue <-
          if (max < 99) {
            for {
              next <- take(q)
              _ <- IO(next mustEqual (max + 1))
            } yield false
          } else {
            IO.pure(true)
          }
      } yield continue

      val Bound = 10 // only try ten times before skipping
      def loop(i: Int): IO[Result] = {
        if (i > Bound) {
          IO.pure(skipped(s"attempted $i times and could not reproduce scenario"))
        } else {
          test flatMap {
            case true => loop(i + 1)
            case false => IO.pure(ok)
          }
        }
      }

      loop(0).replicateA_(100).as(ok)
    }

    "ensure takers are awakened under all circumstances" in real {
      val test = for {
        // _ <- IO.println(s"$prefix >> iterating...")
        q <- constructor(64)
        takenR <- IO.deferred[Option[Int]]

        // start two takers and race them against each other. one populates takenR
        taker1 = take(q) guaranteeCase {
          case Outcome.Succeeded(ioa) =>
            ioa.flatMap(taken => takenR.complete(Some(taken))).void

          case _ =>
            takenR.complete(None).void
        }

        take1 <- taker1.start
        take2 <- take(q) /*.guarantee(IO.println(s"$prefix >> take2 finished"))*/ .start

        // wait for state to quiesce
        _ <- IO.sleep(250.millis)
        // _ <- IO.println(s"$prefix >> waking")
        // race the enqueue against canceling the ref-populating taker
        _ <- IO.both(offer(q, 42), take1.cancel)

        // detect the race condition
        taken <- takenR.get
        // _ <- IO.println(s"$prefix >> received $taken")

        // what we're testing here is that *one* of the two got the element
        _ <- taken match {
          // if take1 got the element, we couldn't reproduce the race condition
          case Some(_) => /*IO.println(s"$prefix >> canceling") >>*/ take2.cancel

          // if neither taker got the element, then we'll be None from take1 and take2.join will hang
          case None => /*IO.println(s"$prefix >> joining") >>*/ take2.join
        }
      } yield ()

      test.parReplicateA(16).as(ok)
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
