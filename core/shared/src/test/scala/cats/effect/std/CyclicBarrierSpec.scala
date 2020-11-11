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
import cats.arrow.FunctionK
import org.specs2.specification.core.Fragments

import scala.concurrent.duration._
import java.util.concurrent.TimeoutException

class CyclicBarrierSpec extends BaseSpec {

  "Cyclic barrier" should {
    cyclicBarrierTests("Cyclic barrier", CyclicBarrier.apply)
    cyclicBarrierTests(
      "Cyclic barrier mapK",
      CyclicBarrier.apply[IO](_).map(_.mapK(FunctionK.id)))
  }

  private def cyclicBarrierTests(
      name: String,
      constructor: Int => IO[CyclicBarrier[IO]]): Fragments = {
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

    s"$name - await releases all fibers" in real {
      for {
        cb <- constructor(2)
        f1 <- cb.await.start
        f2 <- cb.await.start
        r <- (f1.joinAndEmbedNever, f2.joinAndEmbedNever).tupled
        res <- IO(r must beEqualTo(((), ())))
      } yield res
    }

    s"$name - await is blocking" in real {
      for {
        cb <- constructor(2)
        r <- cb.await.timeout(5.millis).attempt
        res <- IO(r must beLike {
          case Left(e) => e must haveClass[TimeoutException]
        })
      } yield res
    }

    s"$name - await is cancelable" in real {
      for {
        cb <- constructor(2)
        f <- cb.await.start
        _ <- IO.sleep(1.milli)
        _ <- f.cancel
        r <- f.join
        res <- IO(r must beEqualTo(Outcome.Canceled()))
      } yield res
    }

    s"$name - reset once full" in real {
      for {
        cb <- constructor(2)
        f1 <- cb.await.start
        f2 <- cb.await.start
        r <- (f1.joinAndEmbedNever, f2.joinAndEmbedNever).tupled
        _ <- IO(r must beEqualTo(((), ())))
        //Should have reset at this point
        r <- cb.await.timeout(5.millis).attempt
        res <- IO(r must beLike {
          case Left(e) => e must haveClass[TimeoutException]
        })
      } yield res
    }

    s"$name - clean up upon cancellation of await" in real {
      for {
        cb <- constructor(2)
        //This should time out and reduce the current capacity to 0 again
        _ <- cb.await.timeout(5.millis).attempt
        //Therefore the capacity should only be 1 when this awaits so will block again
        r <- cb.await.timeout(5.millis).attempt
        res <- IO(r must beLike {
          case Left(e) => e must haveClass[TimeoutException]
        })
      } yield res
    }
  }
}
