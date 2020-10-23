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

class PQueueSpec extends BaseSpec {

  implicit val orderForInt: Order[Int] = Order.fromLessThan((x, y) => x < y)

  "PQueue" should {
    boundedPQueueTests("PQueue", PQueue.bounded)
    boundedPQueueTests("PQueue mapK", PQueue.bounded[IO, Int](_).map(_.mapK(FunctionK.id)))
  }

  private def boundedPQueueTests(
      name: String,
      constructor: Int => IO[PQueue[IO, Int]]): Fragments = {

    s"$name - take when empty" in real {
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
