/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

import org.scalatest.{FunSuite, Matchers}

final class LinkedMapTests extends FunSuite with Matchers with TestUtils {

  test("a new map is empty") {
    val map = LinkedMap.empty[Int, Int]

    assert(map.isEmpty, true)
  }

  test("entries inserted are fetched in order of insertion") {
    val ns = (0 until 10).toList
    val map = ns.foldLeft(LinkedMap.empty[Int, Int])((map, i) => map.updated(i, i))

    assert(map.keys.toList == ns)
    assert(map.values.toList == ns)
  }

  test("removing a key") {
    val ns = (0 until 10).toList
    val map = ns.foldLeft(LinkedMap.empty[Int, Int])((map, i) => map.updated(i, i))
    val n = 2

    assert(map.keys.exists(_ == n))
    assert(map.values.exists(_ == n))

    val newMap = map - n

    assert(!newMap.keys.exists(_ == n))
    assert(!newMap.values.exists(_ == n))
  }

}
