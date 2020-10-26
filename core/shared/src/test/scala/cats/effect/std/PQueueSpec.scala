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
import org.specs2.specification.core.Fragments

import scala.collection.immutable.{Queue => ScalaQueue}
import scala.concurrent.duration._

class BoundedPQueueSpec extends BaseSpec with PQueueTests {
  sequential

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
    tryOfferOnFullTests(name, constructor, false)
    cancelableOfferTests(name, constructor)
    tryOfferTryTakeTests(name, constructor)
    commonTests(name, constructor)

    s"$name - simple order check" in real {
      for {
        q <- constructor(10)
        _ <- List(1, 3, 4, 2, 5).traverse(q.offer(_))
        res <- List.fill(5)(q.take).sequence
        r <- IO(res must beEqualTo(List(1, 2, 3, 4, 5)))
      } yield r
    }

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
    tryOfferOnFullTests(name, _ => constructor, true)
    tryOfferTryTakeTests(name, _ => constructor)
    commonTests(name, _ => constructor)
  }
}

trait PQueueTests { self: BaseSpec =>

  def zeroCapacityConstructionTests(
      name: String,
      constructor: Int => IO[PQueue[IO, Int]]
  ): Fragments = {
    s"$name - raise an exception when constructed with zero capacity" in real {
      val test = IO.defer(constructor(0)).attempt
      test.flatMap { res =>
        IO {
          res must beLike {
            case Left(e) => e must haveClass[IllegalArgumentException]
          }
        }
      }
    }
  }

  def negativeCapacityConstructionTests(
      name: String,
      constructor: Int => IO[PQueue[IO, Int]]
  ): Fragments = {
    s"$name - raise an exception when constructed with a negative capacity" in real {
      val test = IO.defer(constructor(-1)).attempt
      test.flatMap { res =>
        IO {
          res must beLike {
            case Left(e) => e must haveClass[IllegalArgumentException]
          }
        }
      }
    }
  }

  def tryOfferOnFullTests(
      name: String,
      constructor: Int => IO[PQueue[IO, Int]],
      expected: Boolean): Fragments = {
    s"$name - return false on tryOffer when the queue is full" in real {
      for {
        q <- constructor(1)
        _ <- q.offer(0)
        v <- q.tryOffer(1)
        r <- IO(v must beEqualTo(expected))
      } yield r
    }
  }

  def offerTakeOverCapacityTests(
      name: String,
      constructor: Int => IO[PQueue[IO, Int]]
  ): Fragments = {
    s"$name - offer/take over capacity" in real {
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
        q <- constructor(10)
        p <- producer(q, count).start
        c <- consumer(q, count).start
        _ <- p.join
        v <- c.joinAndEmbedNever
        r <- IO(v must beEqualTo(count.toLong * (count - 1) / 2))
      } yield r
    }
  }

  def cancelableOfferTests(name: String, constructor: Int => IO[PQueue[IO, Int]]): Fragments = {
    s"$name - demonstrate cancelable offer" in real {
      for {
        q <- constructor(1)
        _ <- q.offer(1)
        f <- q.offer(2).start
        _ <- IO.sleep(10.millis)
        _ <- f.cancel
        v1 <- q.take
        v2 <- q.tryTake
        r <- IO((v1 must beEqualTo(1)) and (v2 must beEqualTo(None)))
      } yield r
    }
  }

  def tryOfferTryTakeTests(name: String, constructor: Int => IO[PQueue[IO, Int]]): Fragments = {
    s"$name - tryOffer/tryTake" in real {
      val count = 1000

      def producer(q: PQueue[IO, Int], n: Int): IO[Unit] =
        if (n > 0) q.tryOffer(count - n).flatMap {
          case true =>
            producer(q, n - 1)
          case false =>
            IO.cede *> producer(q, n)
        }
        else IO.unit

      def consumer(
          q: PQueue[IO, Int],
          n: Int,
          acc: ScalaQueue[Int] = ScalaQueue.empty
      ): IO[Long] =
        if (n > 0)
          q.tryTake.flatMap {
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
        v <- c.joinAndEmbedNever
        r <- IO(v must beEqualTo(count.toLong * (count - 1) / 2))
      } yield r
    }
  }

  def commonTests(name: String, constructor: Int => IO[PQueue[IO, Int]]): Fragments = {
    s"$name - return None on tryTake when the queue is empty" in real {
      for {
        q <- constructor(1)
        v <- q.tryTake
        r <- IO(v must beNone)
      } yield r
    }

    s"$name - demonstrate sequential offer and take" in real {
      for {
        q <- constructor(1)
        _ <- q.offer(1)
        v1 <- q.take
        _ <- q.offer(2)
        v2 <- q.take
        r <- IO((v1 must beEqualTo(1)) and (v2 must beEqualTo(2)))
      } yield r
    }

    s"$name - demonstrate cancelable take" in real {
      for {
        q <- constructor(1)
        f <- q.take.start
        _ <- IO.sleep(10.millis)
        _ <- f.cancel
        v <- q.tryOffer(1)
        r <- IO(v must beTrue)
      } yield r
    }

    s"$name - async take" in realWithRuntime { implicit rt =>
      for {
        q <- constructor(10)
        _ <- q.offer(1)
        v1 <- q.take
        _ <- IO(v1 must beEqualTo(1))
        f <- IO(q.take.unsafeToFuture())
        _ <- IO(f.value must beEqualTo(None))
        _ <- q.offer(2)
        v2 <- IO.fromFuture(IO.pure(f))
        r <- IO(v2 must beEqualTo(2))
      } yield r
    }
  }
}
