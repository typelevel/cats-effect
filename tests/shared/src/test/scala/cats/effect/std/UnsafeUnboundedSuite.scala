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

class UnsafeUnboundedSuite extends BaseSuite {

  val length = 1000

  testUnit("put and take in order") {
    val q = new UnsafeUnbounded[Int]()

    0.until(length).foreach(q.put(_))
    assertEquals(0.until(length).map(_ => q.take()), 0.until(length))
  }

  testUnit("produce an error when taking from empty - always empty") {
    val _ = intercept[Exception](new UnsafeUnbounded[Unit]().take())
  }

  testUnit("produce an error when taking from empty - emptied") {
    val q = new UnsafeUnbounded[Unit]()

    q.put(())
    q.put(())
    q.take()
    q.take()

    val _ = intercept[Exception](q.take())
  }

  testUnit("put three times, clear one, then take") {
    val q = new UnsafeUnbounded[String]()

    q.put("1")
    val clear = q.put("2")
    q.put("3")

    clear()

    assertEquals(q.take(), "1")
    assertEquals(q.take(), null)
    assertEquals(q.take(), "3")
  }

  real("put and take in parallel") {
    val q = new UnsafeUnbounded[Int]()

    // retry forever until canceled
    def retry[A](ioa: IO[A]): IO[A] =
      ioa.handleErrorWith(_ => IO.cede *> retry(ioa))

    val puts = 1.to(length * 2).toList.parTraverse_(i => IO(q.put(i)))
    val takes = 1.to(length * 2).toList.parTraverse(_ => retry(IO(q.take())))

    for {
      results <- puts &> takes
      _ <- IO(assertEquals(results.sorted, 1.to(length * 2).toList))
    } yield ()
  }

}
