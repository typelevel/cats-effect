/*
 * Copyright 2017 Typelevel
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

import scala.util.{Failure, Success}

class IOTests extends BaseTestsSuite {
  test("unsafeToFuture can yield immediate successful future") {
    val expected = IO(1).unsafeToFuture()
    assert(expected.value === Some(Success(1)))
  }

  test("unsafeToFuture can yield immediate failed future") {
    val dummy = new RuntimeException("dummy")
    val expected = IO.fail(dummy).unsafeToFuture()
    assert(expected.value === Some(Failure(dummy)))
  }

  testAsync("shift works for success") { implicit ec =>
    val expected = IO(1).shift.unsafeToFuture()
    assert(expected.value === None)

    ec.tick()
    assert(expected.value === Some(Success(1)))
  }

  testAsync("shift works for failure") { implicit ec =>
    val dummy = new RuntimeException("dummy")

    val expected = IO.fail(dummy).shift.unsafeToFuture()
    assert(expected.value === None)

    ec.tick()
    assert(expected.value === Some(Failure(dummy)))
  }

  testAsync("shift is stack safe") { implicit ec =>
    val io = (0 until 10000).foldLeft(IO(1))((io, _) => io.shift)

    val expected = io.unsafeToFuture()
    assert(expected.value === None)

    ec.tick()
    assert(expected.value === Some(Success(1)))
  }

  testAsync("shift is stack safe within flatMap loops") { implicit ec =>
    def signal(x: Int): IO[Int] =
      IO(x).shift

    def loop(n: Int): IO[Unit] =
      signal(n).flatMap { v =>
        if (v <= 0) IO.unit else loop(v - 1)
      }

    val expected = loop(100000).unsafeToFuture()
    assert(expected.value === None)

    ec.tick()
    assert(expected.value === Some(Success(())))
  }
}
