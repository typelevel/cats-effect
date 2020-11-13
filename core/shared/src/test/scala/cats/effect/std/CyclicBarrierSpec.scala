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

package cats.effect
package std

import cats.implicits._
import cats.arrow.FunctionK
import scala.concurrent.duration._

import org.specs2.specification.core.Fragments
import java.util.concurrent.TimeoutException
import scala.reflect.ClassTag

class CyclicBarrierSpec extends BaseSpec {

  "Cyclic barrier" should {
    cyclicBarrierTests("Cyclic barrier", CyclicBarrier.apply)
    cyclicBarrierTests(
      "Cyclic barrier mapK",
      CyclicBarrier.apply[IO](_).map(_.mapK(FunctionK.id)))
  }

  implicit class Assertions[A](fa: IO[A]) {
    def mustFailWith[E <: Throwable: ClassTag] =
      fa.attempt.flatMap { res =>
        IO {
          res must beLike {
            case Left(e) => e must haveClass[E]
          }
        }
      }

    def mustEqual(a: A) = fa.flatMap { res =>
      IO(res must beEqualTo(a))
    }
  }

  private def cyclicBarrierTests(
      name: String,
      newBarrier: Int => IO[CyclicBarrier[IO]]): Fragments = {
    s"$name - raise an exception when constructed with a negative capacity" in real {
      IO.defer(newBarrier(-1)).mustFailWith[IllegalArgumentException]
    }

    s"$name - raise an exception when constructed with zero capacity" in real {
      IO.defer(newBarrier(0)).mustFailWith[IllegalArgumentException]
    }

    s"$name - await is blocking" in ticked { implicit ticker =>
      newBarrier(2).flatMap(_.await) must nonTerminate
    }

    s"$name - remaining when constructed" in real {
      newBarrier(5).flatMap { barrier =>
        barrier.awaiting.mustEqual(0) >>
        barrier.remaining.mustEqual(5)
      }
    }

    s"$name - await releases all fibers" in real {
      for {
        barrier <- newBarrier(2)
        f1 <- barrier.await.start
        f2 <- barrier.await.start
        r  = (f1.joinAndEmbedNever, f2.joinAndEmbedNever).tupled
        res <- r.mustEqual(((), ()))
        _ <- barrier.awaiting.mustEqual(0)
      } yield res
    }


    // TODO ticker here
    s"$name - await is cancelable" in real {
      for {
        barrier <- newBarrier(2)
        f <- barrier.await.start
        _ <- IO.sleep(100.millis)
        _ <- f.cancel
        res <- f.join.mustEqual(Outcome.Canceled())
        _ <- barrier.awaiting.mustEqual(0)
      } yield res
    }

    s"$name - reset once full" in real {
      for {
        barrier <- newBarrier(2)
        f1 <- barrier.await.start
        f2 <- barrier.await.start
        r <- (f1.joinAndEmbedNever, f2.joinAndEmbedNever).tupled
        _ <- IO(r must beEqualTo(((), ())))
        //Should have reset at this point
        _ <- barrier.awaiting.mustEqual(0)
        res <- barrier.await.timeout(5.millis).mustFailWith[TimeoutException]
      } yield res
    }

    s"$name - clean up upon cancellation of await" in real {
      for {
        barrier <- newBarrier(2)
        //This should time out and reduce the current capacity to 0 again
        _ <- barrier.await.timeout(5.millis).attempt
        //Therefore the capacity should only be 1 when this awaits so will block again
        _ <- barrier.await.timeout(5.millis).mustFailWith[TimeoutException]
        res <- barrier.awaiting.mustEqual(0)
      } yield res
    }

    /*
     * Original implementation in b31d5a486757f7793851814ec30e056b9c6e40b8
     * had a race between cancellation of an awaiting fiber and
     * resetting the barrier once it's full
     */
    s"$name - race fiber cancel and barrier full" in real {
      val iterations = 100

      val run = for {
        barrier <- newBarrier(2)
        f <- barrier.await.start
        _ <- IO.race(barrier.await, f.cancel)
        awaiting <- barrier.awaiting
        res <- IO(awaiting must beGreaterThanOrEqualTo(0))
      } yield res

      List.fill(iterations)(run).reduce(_ >> _)
    }
  }
}
