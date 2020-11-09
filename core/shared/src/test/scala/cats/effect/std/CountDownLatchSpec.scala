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

import cats.arrow.FunctionK
import org.specs2.specification.core.Fragments

import scala.concurrent.duration._
import cats.effect.kernel.Outcome.Canceled

class CountDownLatchSpec extends BaseSpec {
  sequential

  "CountDownLatch" should {
    boundedQueueTests("CountDownLatch", CountDownLatch.apply[IO])
    boundedQueueTests(
      "CountDownLatch mapK",
      CountDownLatch.apply[IO](_).map(_.mapK(FunctionK.id)))
  }

  private def boundedQueueTests(
      name: String,
      constructor: Int => IO[CountDownLatch[IO]]): Fragments = {

    s"$name - negative initial latches" in {}

    s"$name - zero initial latches" in {}

    s"$name - release and then await" in real {
      for {
        l <- constructor(1)
        _ <- l.release
        u <- l.await
        res <- IO(u must beEqualTo(()))
      } yield res
    }

    s"$name - await with > 1 latch unreleased" in real {
      for {
        l <- constructor(2)
        _ <- l.release
        u <- l.await
        res <- IO(u must beEqualTo(()))
      } yield res
    }

    s"$name - blocking is cancelable" in real {
      for {
        l <- constructor(1)
        fib <- l.await.start
        _ <- IO.sleep(1 milli)
        oc <- fib.join
        res <- IO(oc must beEqualTo(Canceled()))
      } yield res
    }
  }

}
