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

package cats.effect
package internals

class ArrayStackTests extends CatsEffectSuite {
  test("push and pop 8 items") {
    val stack = new ArrayStack[String]()
    var times = 0

    while (times < 10) {
      assert(stack.isEmpty, "stack.isEmpty")
      for (i <- 0 until 8) stack.push(i.toString)

      var list = List.empty[String]
      while (!stack.isEmpty) {
        assert(!stack.isEmpty, "!stack.isEmpty")
        list = stack.pop() :: list
      }

      assertEquals(list, (0 until 8).map(_.toString).toList)
      assertEquals(stack.pop().asInstanceOf[AnyRef], null)
      assertEquals(stack.isEmpty, true)

      times += 1
    }
  }

  test("push and pop 100 items") {
    val stack = new ArrayStack[String]()
    var times = 0

    while (times < 10) {
      assert(stack.isEmpty, "stack.isEmpty")
      for (i <- 0 until 100) stack.push(i.toString)

      var list = List.empty[String]
      while (!stack.isEmpty) {
        assert(!stack.isEmpty, "!stack.isEmpty")
        list = stack.pop() :: list
      }

      assertEquals(list, (0 until 100).map(_.toString).toList)
      assertEquals(stack.pop().asInstanceOf[AnyRef], null)
      assertEquals(stack.isEmpty, true)

      times += 1
    }
  }

  test("pushAll(stack)") {
    val stack = new ArrayStack[String]()
    val stack2 = new ArrayStack[String]()

    for (i <- 0 until 100) stack2.push(i.toString)
    stack.pushAll(stack2)

    var list = List.empty[String]
    while (!stack.isEmpty) {
      assert(!stack.isEmpty)
      list = stack.pop() :: list
    }

    assertEquals(list, (0 until 100).map(_.toString).toList.reverse)
    assertEquals(stack.pop().asInstanceOf[AnyRef], null)
    assertEquals(stack.isEmpty, true)
    assertEquals(stack2.isEmpty, false)
  }

  test("pushAll(iterable)") {
    val stack = new ArrayStack[String]()
    val expected = (0 until 100).map(_.toString).toList
    stack.pushAll(expected)

    var list = List.empty[String]
    while (!stack.isEmpty) {
      assert(!stack.isEmpty)
      list = stack.pop() :: list
    }

    assertEquals(list, expected)
    assertEquals(stack.pop().asInstanceOf[AnyRef], null)
    assertEquals(stack.isEmpty, true)
  }

  test("iterator") {
    val stack = new ArrayStack[String]()
    val expected = (0 until 100).map(_.toString).toList
    for (i <- expected) stack.push(i)
    assertEquals(stack.iteratorReversed.toList, expected.reverse)
  }
}
