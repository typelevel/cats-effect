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

import cats.arrow.FunctionK
import cats.effect.kernel.Outcome.Canceled
import cats.implicits._

import org.specs2.specification.core.Fragments

import scala.concurrent.duration._

import java.util.concurrent.TimeoutException

class CountDownLatchSpec extends BaseSpec {

  "CountDownLatch" should {
    boundedQueueTests("CountDownLatch", CountDownLatch.apply[IO])
    boundedQueueTests(
      "CountDownLatch mapK",
      CountDownLatch.apply[IO](_).map(_.mapK(FunctionK.id)))
  }

  private def boundedQueueTests(
      name: String,
      constructor: Int => IO[CountDownLatch[IO]]): Fragments = {

    s"$name - should raise an exception when constructed with negative initial latches" in real {
      val test = IO.defer(constructor(-1)).attempt
      test.flatMap { res =>
        IO {
          res must beLike { case Left(e) => e must haveClass[IllegalArgumentException] }
        }
      }
    }

    s"$name - should raise an exception when constructed with zero initial latches" in real {
      val test = IO.defer(constructor(0)).attempt
      test.flatMap { res =>
        IO {
          res must beLike { case Left(e) => e must haveClass[IllegalArgumentException] }
        }
      }
    }

    s"$name - release and then await should complete" in real {
      for {
        l <- constructor(1)
        _ <- l.release
        r <- l.await
        res <- IO(r must beEqualTo(()))
      } yield res
    }

    s"$name - await and then release should complete" in real {
      for {
        l <- constructor(1)
        f <- l.await.start
        _ <- IO.sleep(1.milli)
        _ <- l.release
        r <- f.joinWithNever
        res <- IO(r must beEqualTo(()))
      } yield res
    }

    s"$name - await with > 1 latch unreleased should block" in real {
      for {
        l <- constructor(2)
        _ <- l.release
        r <- l.await.timeout(5.millis).attempt
        res <- IO(r must beLike { case Left(e) => e must haveClass[TimeoutException] })
      } yield res
    }

    s"$name - multiple awaits should all complete" in real {
      for {
        l <- constructor(1)
        f1 <- l.await.start
        f2 <- l.await.start
        _ <- IO.sleep(1.milli)
        _ <- l.release
        r <- (f1.joinWithNever, f2.joinWithNever).tupled
        res <- IO(r must beEqualTo(((), ())))
      } yield res
    }

    s"$name - should release when latches == 0" in real {
      for {
        l <- constructor(1)
        _ <- l.release
        r <- l.release
        res <- IO(r must beEqualTo(()))
      } yield res
    }

    s"$name - blocking is cancelable" in real {
      for {
        l <- constructor(1)
        fib <- l.await.start
        _ <- IO.sleep(1.milli)
        _ <- fib.cancel
        oc <- fib.join
        res <- IO(oc must beEqualTo(Canceled()))
      } yield res
    }
  }

}
