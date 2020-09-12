/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

package cats.effect.internals

import munit.FunSuite

class RingBufferTests extends FunSuite with TestUtils {
  test("empty ring buffer") {
    val buffer = new RingBuffer[Integer](2)
    assertEquals(buffer.isEmpty, true)
  }

  test("non-empty ring buffer") {
    val buffer = new RingBuffer[Integer](2)
    buffer.push(0)
    assertEquals(buffer.isEmpty, false)
  }

  test("writing elements") {
    val buffer = new RingBuffer[Integer](2)
    for (i <- 0 to 3) buffer.push(i)
    assertEquals(buffer.toList.map(_.toInt), List(3, 2, 1, 0))
  }

  test("overwriting elements") {
    val buffer = new RingBuffer[Integer](2)
    for (i <- 0 to 100) buffer.push(i)
    assertEquals(buffer.toList.map(_.toInt), List(100, 99, 98, 97))
  }
}
