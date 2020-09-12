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

import cats.effect.IO
import munit.FunSuite

class IOConnectionTests extends FunSuite {
  test("initial push") {
    var effect = 0
    val initial = IO(effect += 1)
    val c = IOConnection()
    c.push(initial)
    c.cancel.unsafeRunSync()
    assertEquals(effect, 1)
    c.cancel.unsafeRunSync()
    assertEquals(effect, 1)
  }

  test("cancels after being canceled") {
    var effect = 0
    val initial = IO(effect += 1)
    val c = IOConnection()
    c.push(initial)

    c.cancel.unsafeRunSync()
    assertEquals(effect, 1)

    c.cancel.unsafeRunSync()
    assertEquals(effect, 1)

    c.push(initial)
    assertEquals(effect, 2)
  }

  test("push two, pop one") {
    var effect = 0
    val initial1 = IO(effect += 1)
    val initial2 = IO(effect += 2)

    val c = IOConnection()
    c.push(initial1)
    c.push(initial2)
    c.pop()

    c.cancel.unsafeRunSync()
    assertEquals(effect, 1)
  }

  test("cancel the second time is a no-op") {
    var effect = 0
    val bc = IO(effect += 1)
    val c = IOConnection()
    c.push(bc)

    c.cancel.unsafeRunSync()
    assertEquals(effect, 1)
    c.cancel.unsafeRunSync()
    assertEquals(effect, 1)
  }

  test("push two, pop two") {
    var effect = 0
    val initial1 = IO(effect += 1)
    val initial2 = IO(effect += 2)

    val c = IOConnection()
    c.push(initial1)
    c.push(initial2)
    assertEquals(c.pop(), initial2)
    assertEquals(c.pop(), initial1)
    c.cancel.unsafeRunSync()

    assertEquals(effect, 0)
  }

  test("uncancelable returns same reference") {
    val ref1 = IOConnection.uncancelable
    val ref2 = IOConnection.uncancelable
    assertEquals(ref1, ref2)
  }

  test("uncancelable reference cannot be canceled") {
    val ref = IOConnection.uncancelable
    assertEquals(ref.isCanceled, false)
    ref.cancel.unsafeRunSync()
    assertEquals(ref.isCanceled, false)
  }

  test("uncancelable.pop") {
    val ref = IOConnection.uncancelable
    assertEquals(ref.pop(), IO.unit)

    ref.push(IO.pure(()))
    assertEquals(ref.pop(), IO.unit)
  }

  test("uncancelable.push never cancels the given cancelable") {
    val ref = IOConnection.uncancelable
    ref.cancel.unsafeRunSync()

    var effect = 0
    val c = IO(effect += 1)
    ref.push(c)
    assertEquals(effect, 0)
  }
}
