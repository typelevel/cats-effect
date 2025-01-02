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

import cats.arrow.FunctionK
import cats.syntax.all._

import scala.collection.immutable.{Queue => ScalaQueue}
import scala.concurrent.duration._

class BoundedQueueSuite extends BaseSuite with QueueTests[Queue] with DetectPlatform {

  boundedQueueTests(
    "BoundedQueue (concurrent)",
    i => if (i == 0) Queue.synchronous else Queue.boundedForConcurrent(i))
  boundedQueueTests("BoundedQueue (async)", Queue.bounded)

  real("BoundedQueue (unsafe) - permit tryOffer when empty") {
    Queue.unsafeBounded[IO, Int](1024) flatMap { q =>
      for {
        attempt <- IO(q.unsafeTryOffer(42))
        _ <- IO(assert(attempt))
        i <- q.take
        _ <- IO(assertEquals(i, 42))
      } yield ()
    }
  }

  real("BoundedQueue (unsafe) - forbid tryOffer when full") {
    Queue.unsafeBounded[IO, Int](8) flatMap { q =>
      for {
        _ <- 0.until(8).toList.traverse_(q.offer(_))
        attempt <- IO(q.unsafeTryOffer(42))
        _ <- IO(assert(!attempt))
      } yield ()
    }
  }

  real("BoundedQueue constructor - not OOM") {
    Queue.bounded[IO, Unit](Int.MaxValue).void
  }

  boundedQueueTests("BoundedQueue mapK", Queue.bounded[IO, Int](_).map(_.mapK(FunctionK.id)))

  ticked("synchronous queue - respect fifo order") { implicit ticker =>
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

    assertCompleteAs(test, 0.until(5).toList)
  }

  real("synchronous queue - not lose offer when taker is canceled during exchange") {
    val test = for {
      q <- Queue.synchronous[IO, Unit]
      latch <- CountDownLatch[IO](2)
      offererDone <- IO.ref(false)

      _ <- (latch.release *> latch.await *> q.offer(())).guarantee(offererDone.set(true)).start
      taker <- (latch.release *> latch.await *> q.take).start

      _ <- latch.await
      _ <- taker.cancel

      // we should either have received the value successfully, or we left the value in queue
      // what we *don't* want is to remove the value and then lose it due to cancelation
      oc <- taker.join

      _ <-
        if (oc.isCanceled) {
          // we (maybe) hit the race condition
          // if we lost the value, q.take will hang
          offererDone.get.flatMap(b => IO(assert(!b))) *> q.take
        } else {
          // we definitely didn't hit the race condition, because we got the value in taker
          IO.unit
        }
    } yield ()

    test.parReplicateA_(if (isJVM) 10000 else 1)
  }

  real(
    "synchronous queue - not lose takers when offerer is canceled and there are no other takers") {
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

    test.parReplicateA_(if (isJS || isNative) 1 else 1000)
  }

  private def boundedQueueTests(name: String, constructor: Int => IO[Queue[IO, Int]]) = {
    real(s"$name - demonstrate offer and take with zero capacity") {
      for {
        q <- constructor(0)
        _ <- q.offer(1).start
        v1 <- q.take
        f <- q.take.start
        _ <- q.offer(2)
        v2 <- f.joinWithNever
        _ <- IO(assertEquals(v1, 1))
        _ <- IO(assertEquals(v2, 2))
      } yield ()
    }

    ticked(s"$name - respect fifo order with zero capacity") { implicit ticker =>
      val test = for {
        q <- constructor(0)

        _ <- 0.until(5).toList traverse { i => (IO.sleep(i.millis) *> q.offer(i)).start }

        _ <- IO.sleep(10.millis)

        results <- q.take.replicateA(5)
      } yield results

      assertCompleteAs(test, List(0, 1, 2, 3, 4))
    }

    ticked(s"$name - demonstrate cancelable offer with zero capacity") { implicit ticker =>
      val test1 = for {
        q <- constructor(0)

        offerer1 <- (IO.sleep(1.millis) *> q.offer(0)).start
        offerer2 <- (IO.sleep(2.millis) *> q.offer(1)).start
        _ <- IO.sleep(10.millis)

        _ <- offerer1.cancel

        result <- q.take
        outcome <- offerer2.join
      } yield (result, outcome.isSuccess)

      assertCompleteAs(test1, (1, true))

      val test2 = for {
        q <- constructor(0)

        offerer1 <- (IO.sleep(1.millis) *> q.offer(0)).start
        offerer2 <- (IO.sleep(2.millis) *> q.offer(1)).start
        _ <- IO.sleep(10.millis)

        _ <- offerer2.cancel

        result <- q.take
        outcome <- offerer1.join
      } yield (result, outcome.isSuccess)

      assertCompleteAs(test2, (0, true))
    }

    ticked(s"$name - demonstrate cancelable take with zero capacity") { implicit ticker =>
      val test1 = for {
        q <- constructor(0)

        taker1 <- (IO.sleep(1.millis) *> q.take).start
        taker2 <- (IO.sleep(2.millis) *> q.take).start
        _ <- IO.sleep(10.millis)

        _ <- taker1.cancel

        _ <- q.offer(42)
        result <- taker2.joinWithNever
      } yield result

      assertCompleteAs(test1, 42)

      val test2 = for {
        q <- constructor(0)

        taker1 <- (IO.sleep(1.millis) *> q.take).start
        taker2 <- (IO.sleep(2.millis) *> q.take).start
        _ <- IO.sleep(10.millis)

        _ <- taker1.cancel

        _ <- q.offer(42)
        result <- taker2.joinWithNever
      } yield result

      assertCompleteAs(test2, 42)
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

    real(s"$name - offer/take with zero capacity") {
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
        r <- IO(assertEquals(v, count.toLong * (count - 1) / 2))
      } yield r
    }

    real(s"$name - offer/take from many fibers simultaneously") {
      val fiberCount = 50

      val expected = 0.until(fiberCount) flatMap { i => 0.until(i).map(_ => i) }

      def producer(q: Queue[IO, Int], id: Int): IO[Unit] =
        q.offer(id).replicateA_(id)

      def consumer(q: Queue[IO, Int], num: Int): IO[List[Int]] =
        q.take.replicateA(num)

      for {
        q <- constructor(64)

        produce = 0.until(fiberCount).toList.parTraverse_(producer(q, _))
        consume = 0
          .until(fiberCount)
          .toList
          .parTraverse(consumer(q, _))
          .map(_.flatMap(identity))

        results <- produce &> consume

        _ <- IO(assertEquals(results.sorted, expected.toList))
      } yield ()
    }

    real(s"$name - offer/take at high contention") {
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

      action
    }

    real(s"$name - offer/take with a single consumer and high contention") {
      constructor(8) flatMap { q =>
        val offerer = List.fill(8)(List.fill(8)(0)).parTraverse_(_.traverse(q.offer(_)))

        val iter = if (isJVM) 1000 else 1
        (offerer &> 0.until(8 * 8).toList.traverse_(_ => q.take)).replicateA_(iter) *>
          q.size.flatMap(s => IO(assertEquals(s, 0)))
      }
    }

    real(s"$name - offer/take/takeN with a single consumer and high contention") {
      constructor(8) flatMap { q =>
        val offerer = List.fill(8)(List.fill(8)(0)).parTraverse_(_.traverse(q.offer(_)))

        def taker(acc: Int): IO[Unit] = {
          if (acc >= 8 * 8) {
            IO.unit
          } else {
            q.tryTakeN(None) flatMap {
              case Nil =>
                q.take *> taker(acc + 1)

              case as =>
                taker(acc + as.length)
            }
          }
        }

        (offerer &> taker(0)).replicateA_(if (isJVM) 1000 else 1) *>
          q.size.flatMap(s => IO(assertEquals(s, 0)))
      }
    }

    negativeCapacityConstructionTests(name, constructor)
    tryOfferOnFullTests(name, constructor, _.offer(_), _.tryOffer(_), false)
    cancelableOfferTests(name, constructor, _.offer(_), _.take, _.tryTake)
    cancelableOfferBoundedTests(name, constructor, _.offer(_), _.take, _.tryTakeN(_))
    cancelableTakeTests(name, constructor, _.offer(_), _.take)
    tryOfferTryTakeTests(name, constructor, _.tryOffer(_), _.tryTake)
    commonTests(name, constructor, _.offer(_), _.tryOffer(_), _.take, _.tryTake, _.size)
    batchTakeTests(name, constructor, _.offer(_), _.tryTakeN(_))
    batchOfferTests(name, constructor, _.tryOfferN(_), _.tryTakeN(_))
    boundedBatchOfferTests(name, constructor, _.tryOfferN(_), _.tryTakeN(_))
  }
}

class UnboundedQueueSuite extends BaseSuite with QueueTests[Queue] {

  unboundedQueueTests("UnboundedQueue (concurrent)", Queue.unboundedForConcurrent)

  unboundedQueueTests("UnboundedQueue (async)", Queue.unboundedForAsync)

  real("UnboundedQueue (unsafe) - pass a value from unsafeOffer to take") {
    Queue.unsafeUnbounded[IO, Int] flatMap { q =>
      for {
        _ <- IO(q.unsafeOffer(42))
        i <- q.take
        _ <- IO(assertEquals(i, 42))
      } yield ()
    }
  }

  unboundedQueueTests("UnboundedQueue mapk", Queue.unbounded[IO, Int].map(_.mapK(FunctionK.id)))

  private def unboundedQueueTests(name: String, constructor: IO[Queue[IO, Int]]) = {
    tryOfferOnFullTests(name, _ => constructor, _.offer(_), _.tryOffer(_), true)
    tryOfferTryTakeTests(name, _ => constructor, _.tryOffer(_), _.tryTake)
    commonTests(name, _ => constructor, _.offer(_), _.tryOffer(_), _.take, _.tryTake, _.size)
    batchTakeTests(name, _ => constructor, _.offer(_), _.tryTakeN(_))
    batchOfferTests(name, _ => constructor, _.tryOfferN(_), _.tryTakeN(_))
    cancelableTakeTests(name, _ => constructor, _.offer(_), _.take)
  }
}

class DroppingQueueSuite extends BaseSuite with QueueTests[Queue] {

  droppingQueueTests(
    "DroppingQueue (concurrent)",
    i => if (i < 1) Queue.dropping(i) else Queue.droppingForConcurrent(i))
  droppingQueueTests("DroppingQueue (async)", Queue.dropping)
  droppingQueueTests("DroppingQueue mapK", Queue.dropping[IO, Int](_).map(_.mapK(FunctionK.id)))

  private def droppingQueueTests(name: String, constructor: Int => IO[Queue[IO, Int]]) = {
    negativeCapacityConstructionTests(name, constructor)
    zeroCapacityConstructionTests(name, constructor)
    tryOfferOnFullTests(name, constructor, _.offer(_), _.tryOffer(_), false)
    cancelableOfferTests(name, constructor, _.offer(_), _.take, _.tryTake)
    cancelableTakeTests(name, constructor, _.offer(_), _.take)
    tryOfferTryTakeTests(name, constructor, _.tryOffer(_), _.tryTake)
    commonTests(name, constructor, _.offer(_), _.tryOffer(_), _.take, _.tryTake, _.size)
    batchTakeTests(name, constructor, _.offer(_), _.tryTakeN(_))
    batchOfferTests(name, constructor, _.tryOfferN(_), _.tryTakeN(_))
  }
}

class CircularBufferQueueSuite extends BaseSuite with QueueTests[Queue] {

  slidingQueueTests("CircularBuffer", Queue.circularBuffer)
  slidingQueueTests(
    "CircularBuffer mapK",
    Queue.circularBuffer[IO, Int](_).map(_.mapK(FunctionK.id)))

  private def slidingQueueTests(name: String, constructor: Int => IO[Queue[IO, Int]]) = {
    negativeCapacityConstructionTests(name, constructor)
    zeroCapacityConstructionTests(name, constructor)
    tryOfferOnFullTests(name, constructor, _.offer(_), _.tryOffer(_), true)
    commonTests(name, constructor, _.offer(_), _.tryOffer(_), _.take, _.tryTake, _.size)
    batchTakeTests(name, constructor, _.offer(_), _.tryTakeN(_))
    batchOfferTests(name, constructor, _.tryOfferN(_), _.tryTakeN(_))
  }
}

trait QueueTests[Q[_[_], _]] { self: BaseSuite =>

  def zeroCapacityConstructionTests(name: String, constructor: Int => IO[Q[IO, Int]]) = {
    real(s"$name - should raise an exception when constructed with zero capacity") {
      val test = IO.defer(constructor(0)).attempt
      test.flatMap { res =>
        IO {
          res match {
            case Left(e) => assert(e.isInstanceOf[IllegalArgumentException])
            case Right(v) => fail(s"Expected Left, got $v")
          }
        }
      }
    }
  }

  def negativeCapacityConstructionTests(name: String, constructor: Int => IO[Q[IO, Int]]) = {
    real(s"$name - should raise an exception when constructed with a negative capacity") {
      val test = IO.defer(constructor(-1)).attempt
      test.flatMap { res =>
        IO {
          res match {
            case Left(e) => assert(e.isInstanceOf[IllegalArgumentException])
            case Right(v) => fail(s"Expected Left, got $v")
          }
        }
      }
    }
  }

  def batchOfferTests(
      name: String,
      constructor: Int => IO[Q[IO, Int]],
      tryOfferN: (Q[IO, Int], List[Int]) => IO[List[Int]],
      tryTakeN: (Q[IO, Int], Option[Int]) => IO[List[Int]],
      transform: List[Int] => List[Int] = identity
  ) = {
    real(s"$name - should offer all records when there is room") {
      for {
        q <- constructor(5)
        offerR <- tryOfferN(q, List(1, 2, 3, 4, 5))
        takeR <- tryTakeN(q, None)
        r <- IO {
          assertEquals(transform(takeR), List(1, 2, 3, 4, 5))
          assertEquals(offerR, List.empty)
        }
      } yield r
    }
  }

  def boundedBatchOfferTests(
      name: String,
      constructor: Int => IO[Q[IO, Int]],
      tryOfferN: (Q[IO, Int], List[Int]) => IO[List[Int]],
      tryTakeN: (Q[IO, Int], Option[Int]) => IO[List[Int]],
      transform: List[Int] => List[Int] = identity
  ) = {
    real(s"$name - should offer some records when the queue is full") {
      for {
        q <- constructor(5)
        offerR <- tryOfferN(q, List(1, 2, 3, 4, 5, 6, 7))
        takeR <- tryTakeN(q, None)
        r <- IO {
          assertEquals(transform(takeR), List(1, 2, 3, 4, 5))
          assertEquals(offerR, List(6, 7))
        }
      } yield r
    }
  }

  def batchTakeTests(
      name: String,
      constructor: Int => IO[Q[IO, Int]],
      offer: (Q[IO, Int], Int) => IO[Unit],
      tryTakeN: (Q[IO, Int], Option[Int]) => IO[List[Int]],
      transform: List[Int] => List[Int] = identity) = {

    real(s"$name - take batches for all records when None is provided") {
      for {
        q <- constructor(5)
        _ <- offer(q, 1)
        _ <- offer(q, 2)
        _ <- offer(q, 3)
        _ <- offer(q, 4)
        _ <- offer(q, 5)
        b <- tryTakeN(q, None)
        r <- IO(assertEquals(transform(b), List(1, 2, 3, 4, 5)))
      } yield r
    }

    real(s"$name - take batches for all records when maxN is provided") {
      for {
        q <- constructor(5)
        _ <- offer(q, 1)
        _ <- offer(q, 2)
        _ <- offer(q, 3)
        _ <- offer(q, 4)
        _ <- offer(q, 5)
        b <- tryTakeN(q, Some(5))
        r <- IO(assertEquals(transform(b), List(1, 2, 3, 4, 5)))
      } yield r
    }

    real(s"$name - take all records when maxN > queue size") {
      for {
        q <- constructor(5)
        _ <- offer(q, 1)
        _ <- offer(q, 2)
        _ <- offer(q, 3)
        _ <- offer(q, 4)
        _ <- offer(q, 5)
        b <- tryTakeN(q, Some(7))
        r <- IO(assertEquals(transform(b), List(1, 2, 3, 4, 5)))
      } yield r
    }

    real(s"$name - be empty when queue is empty") {
      for {
        q <- constructor(5)
        b <- tryTakeN(q, Some(5))
        r <- IO(assertEquals(transform(b), List.empty))
      } yield r
    }

    real(s"$name - raise an exception when maxN is not > 0") {
      val toAttempt = for {
        q <- constructor(5)
        _ <- tryTakeN(q, Some(-1))
      } yield ()

      val test = IO.defer(toAttempt).attempt
      test.flatMap { res =>
        IO {
          res match {
            case Left(e) => assert(e.isInstanceOf[IllegalArgumentException])
            case Right(v) => fail(s"Expected Left, got $v")
          }
        }
      }
    }

    real(s"$name - release one offerer when queue is full") {
      val test = for {
        q <- constructor(5)
        _ <- offer(q, 0).replicateA_(5)

        latch <- IO.deferred[Unit]
        expected <- CountDownLatch[IO](1)

        _ <- (latch.complete(()) *> offer(q, 0) *> expected.release).start

        _ <- latch.get
        results <- tryTakeN(q, None)

        _ <-
          if (results.nonEmpty)
            expected.await
          else
            IO.println("did not take any results")
      } yield ()

      test.parReplicateA_(10)
    }

    real(s"$name - release all offerers when queue is full") {
      val test = for {
        q <- constructor(5)
        _ <- offer(q, 0).replicateA_(5)

        latch <- CountDownLatch[IO](5)
        expected <- CountDownLatch[IO](5)

        _ <- (latch.release *> offer(q, 0) *> expected.release).start.replicateA_(5)

        _ <- latch.await
        results <- tryTakeN(q, None)

        // release whatever we didn't receive
        _ <- expected.release.replicateA_(5 - results.length)
        _ <- expected.await
      } yield ()

      test.parReplicateA_(10)
    }
  }

  def tryOfferOnFullTests(
      name: String,
      constructor: Int => IO[Q[IO, Int]],
      offer: (Q[IO, Int], Int) => IO[Unit],
      tryOffer: (Q[IO, Int], Int) => IO[Boolean],
      expected: Boolean) = {

    real(s"$name - return false on tryOffer when the queue is full") {
      for {
        q <- constructor(2)
        _ <- offer(q, 0)
        _ <- offer(q, 0)
        v <- tryOffer(q, 1)
        r <- IO(assertEquals(v, expected))
      } yield r
    }
  }

  def offerTakeOverCapacityTests(
      name: String,
      constructor: Int => IO[Q[IO, Int]],
      offer: (Q[IO, Int], Int) => IO[Unit],
      take: Q[IO, Int] => IO[Int]
  ) = {

    real(s"$name - offer/take over capacity") {
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
        r <- IO(assertEquals(v, count.toLong * (count - 1) / 2))
      } yield r
    }
  }

  def cancelableOfferTests(
      name: String,
      constructor: Int => IO[Q[IO, Int]],
      offer: (Q[IO, Int], Int) => IO[Unit],
      take: Q[IO, Int] => IO[Int],
      tryTake: Q[IO, Int] => IO[Option[Int]]) = {

    real(s"$name - demonstrate cancelable offer") {
      for {
        q <- constructor(2)
        _ <- offer(q, 1)
        _ <- offer(q, 1)
        f <- offer(q, 2).start
        _ <- IO.sleep(10.millis)
        _ <- f.cancel
        v1 <- take(q)
        _ <- take(q)
        v2 <- tryTake(q)

        r <- IO {
          assertEquals(v1, 1)
          assertEquals(v2, None)
        }
      } yield r
    }

    real(s"$name - ensure offerers are awakened under all circumstances") {
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

      test.parReplicateA_(16)
    }
  }

  def cancelableOfferBoundedTests(
      name: String,
      constructor: Int => IO[Q[IO, Int]],
      offer: (Q[IO, Int], Int) => IO[Unit],
      take: Q[IO, Int] => IO[Int],
      tryTakeN: (Q[IO, Int], Option[Int]) => IO[List[Int]]) = {

    ticked(s"$name - ensure offerers are awakened by tryTakeN after cancelation") {
      implicit ticker =>
        val test = for {
          q <- constructor(4)

          // fill the queue
          _ <- 0.until(4).toList.traverse_(offer(q, _))

          offerers <- 0.until(4).toList traverse { i =>
            // the sleep is to ensure the offerers are ordered
            (IO.sleep(i.millis) *> offer(q, 10 + i)).start
          }

          // allow quiescence
          _ <- IO.sleep(10.millis)

          // take the *not*-first offerer and cancel it
          _ <- offerers(1).cancel

          // fill up the offerers again
          _ <- offer(q, 20).start
          _ <- IO.sleep(1.millis)

          // drain the queue. this most notify *all* offerers
          taken1 <- tryTakeN(q, None)
          _ <- IO(assertEquals(taken1, List(0, 1, 2, 3)))

          // drain it again
          // if the offerers weren't awakened, this will hang
          taken2 <- 0.until(4).toList.traverse(_ => take(q))
          _ <- IO(assertEquals(taken2.sorted, List(10, 12, 13, 20)))
        } yield ()

        assertCompleteAs(test, ())
    }
  }

  def cancelableTakeTests(
      name: String,
      constructor: Int => IO[Q[IO, Int]],
      offer: (Q[IO, Int], Int) => IO[Unit],
      take: Q[IO, Int] => IO[Int]) = {

    real(s"$name - not lose data on canceled take") {
      val test = for {
        q <- constructor(100)

        _ <- 0.until(100).toList.traverse_(offer(q, _) *> IO.cede).start

        results <- IO.ref(-1)
        latch <- IO.deferred[Unit]

        consumer = for {
          _ <- latch.complete(())

          // grab an element and attempt to atomically set it into `results`
          // if `take` itself is not atomic, then we might lose an element here
          _ <- IO.uncancelable(_(take(q)).flatMap(results.set(_))).replicateA_(100)
        } yield ()

        consumerFiber <- consumer.start

        _ <- latch.get
        // note that, since cancelation backpressures, we're guaranteed to finish setting `results`
        _ <- consumerFiber.cancel

        // grab the last result taken
        max <- results.get

        continue <-
          if (max < 99) {
            for {
              next <- take(q)

              // if this doesn't line up, then it means that we lost an element in the middle
              // namely, when we were canceled, we took an element from the queue without producing
              _ <- IO(assertEquals(next, max + 1))
            } yield false
          } else {
            // we consumed the whole sequence from the queue before cancelation, so no possible race
            IO.pure(true)
          }
      } yield continue

      val Bound = 10 // only try ten times before skipping
      def loop(i: Int): IO[Unit] = {
        // we try iterating a few times to get canceled in the middle
        if (i > Bound) {
          IO.println(s"attempted $i times and could not reproduce scenario")
        } else {
          test flatMap {
            case true => loop(i + 1)
            case false => IO.unit
          }
        }
      }

      // even if we replicate the "cancelation in the middle", we might not hit a race condition
      // replicate this a bunch of times to build confidence that it works
      loop(0).replicateA_(100)
    }

    real(s"$name - ensure takers are awakened under all circumstances") {
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
          case Some(_) => take2.cancel

          // if neither taker got the element, then we'll be None from take1 and take2.join will hang
          case None => take2.join
        }
      } yield ()

      test.parReplicateA_(16)
    }
  }

  def tryOfferTryTakeTests(
      name: String,
      constructor: Int => IO[Q[IO, Int]],
      tryOffer: (Q[IO, Int], Int) => IO[Boolean],
      tryTake: Q[IO, Int] => IO[Option[Int]]) = {

    real(s"$name - tryOffer/tryTake") {
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
        r <- IO(assertEquals(v, count.toLong * (count - 1) / 2))
      } yield r
    }
  }

  def commonTests(
      name: String,
      constructor: Int => IO[Q[IO, Int]],
      offer: (Q[IO, Int], Int) => IO[Unit],
      tryOffer: (Q[IO, Int], Int) => IO[Boolean],
      take: Q[IO, Int] => IO[Int],
      tryTake: Q[IO, Int] => IO[Option[Int]],
      size: Q[IO, Int] => IO[Int]) = {

    real(s"$name - should return the queue size when added to") {
      for {
        q <- constructor(2)
        _ <- offer(q, 1)
        _ <- take(q)
        _ <- offer(q, 2)
        sz <- size(q)
        r <- IO(assertEquals(sz, 1))
      } yield r
    }

    real(s"$name - should return None on tryTake when the queue is empty") {
      for {
        q <- constructor(2)
        v <- tryTake(q)
        r <- IO(assertEquals(v, None))
      } yield r
    }

    real(s"$name - demonstrate sequential offer and take") {
      for {
        q <- constructor(2)
        _ <- offer(q, 1)
        v1 <- take(q)
        _ <- offer(q, 2)
        v2 <- take(q)
        r <- IO {
          assertEquals(v1, 1)
          assertEquals(v2, 2)
        }
      } yield r
    }

    real(s"$name - demonstrate cancelable take") {
      for {
        q <- constructor(2)
        f <- take(q).start
        _ <- IO.sleep(10.millis)
        _ <- f.cancel
        v <- tryOffer(q, 1)
        r <- IO(assert(v))
      } yield r
    }

    realWithRuntime("async take") { implicit rt =>
      for {
        q <- constructor(10)
        _ <- offer(q, 1)
        v1 <- take(q)
        _ <- IO(assertEquals(v1, 1))
        f <- IO(take(q).unsafeToFuture())
        _ <- IO(assertEquals(f.value, None))
        _ <- offer(q, 2)
        v2 <- IO.fromFuture(IO.pure(f))
        r <- IO(assertEquals(v2, 2))
      } yield r
    }

    ticked(s"$name - should return the queue size when take precedes offer") {
      implicit ticker =>
        assertCompleteAs(
          constructor(10).flatMap { q =>
            take(q).background.use { took =>
              IO.sleep(1.second) *> offer(q, 1) *> took *> size(q)
            }
          },
          0)
    }

    ticked(s"$name - should return the queue size when take precedes tryOffer") {
      implicit ticker =>
        assertCompleteAs(
          constructor(10).flatMap { q =>
            take(q).background.use { took =>
              IO.sleep(1.second) *> tryOffer(q, 1) *> took *> size(q)
            }
          },
          0)
    }
  }
}
