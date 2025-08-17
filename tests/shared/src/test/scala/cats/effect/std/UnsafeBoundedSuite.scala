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

package cats.effect
package std

import cats.syntax.all._

import scala.concurrent.duration._

class UnsafeBoundedSuite extends BaseSuite {
  import Queue.UnsafeBounded

  override def executionTimeout = super.executionTimeout * 3 / 2

  // NB: emperically, it seems this needs to be > availableProcessors() to be effective
  val length = 1000

  testUnit("sequential all") {
    val q = new UnsafeBounded[Int](length)

    0.until(length).foreach(q.put(_))
    assertEquals(0.until(length).map(_ => q.take()).toList, 0.until(length).toList)
  }

  real("enqueue max items and dequeue in order - parallel put, parallel take") {
    val q = new UnsafeBounded[Int](length)

    val test = for {
      _ <- 0.until(length).toList.parTraverse_(i => IO(q.put(i)))
      results <- 0.until(length).toList.parTraverse(_ => IO(q.take()))
      _ <- IO(assertEquals(results.sorted, 0.until(length).toList))
    } yield ()

    test.timeoutTo(16.seconds, IO(assert(false)))
  }

  real("enqueue max items and dequeue in order - parallel put and take") {
    val q = new UnsafeBounded[Int](length)

    // retry forever until canceled
    def retry[A](ioa: IO[A]): IO[A] =
      ioa.handleErrorWith(_ => IO.cede *> retry(ioa))

    val puts = 1.to(length * 2).toList.parTraverse_(i => retry(IO(q.put(i))))
    val takes = 1.to(length * 2).toList.parTraverse(_ => retry(IO(q.take())))

    val test = for {
      results <- puts &> takes
      _ <- IO(assertEquals(q.size(), 0))
      _ <- IO(assertEquals(results.sorted, 1.to(length * 2).toList))
    } yield ()

    test.timeoutTo(30.seconds, IO(assert(false)))
  }

  testUnit("produce failure when putting over bound") {
    val q = new UnsafeBounded[Unit](10)
    val _ = intercept[Exception](0.until(11).foreach(_ => q.put(())))
  }

  testUnit("produce failure when taking while empty - without changes") {
    val q = new UnsafeBounded[Unit](10)
    val _ = intercept[Exception](q.take())
  }

  testUnit("produce failure when taking while empty - after put and take") {
    val q = new UnsafeBounded[Unit](10)

    0.until(5).foreach(_ => q.put(()))
    0.until(5).foreach(_ => q.take())

    val _ = intercept[Exception](q.take())
  }
}
