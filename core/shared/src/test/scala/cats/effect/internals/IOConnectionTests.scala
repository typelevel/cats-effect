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

class IOConnectionTests extends FunSuite with Matchers {
  test("initial push") {
    var effect = 0
    val initial = BooleanCancelable(() => effect += 1)
    val c = IOConnection()
    c.push(initial)
    c.cancel()
    effect shouldBe 1
  }

  test("cancels after being canceled") {
    var effect = 0
    val initial = BooleanCancelable(() => effect += 1)
    val c = IOConnection()
    c.cancel()
    c push initial
    effect shouldBe 1
  }

  test("push two, pop one") {
    var effect = 0
    val initial1 = BooleanCancelable(() => effect += 1)
    val initial2 = BooleanCancelable(() => effect += 2)

    val c = IOConnection()
    c.push(initial1)
    c.push(initial2)
    c.pop()
    c.cancel()

    effect shouldBe 1
  }

  test("cancel the second time is a no-op") {
    val bc = BooleanCancelable()
    val c = IOConnection()
    c.push(bc)

    c.cancel()
    assert(bc.isCanceled, "bc.isCanceled")
    c.cancel()
    assert(bc.isCanceled, "bc.isCanceled")
  }

  test("push two, pop two") {
    var effect = 0
    val initial1 = BooleanCancelable(() => effect += 1)
    val initial2 = BooleanCancelable(() => effect += 2)

    val c = IOConnection()
    c.push(initial1)
    c.push(initial2)
    c.pop() shouldBe initial2
    c.pop() shouldBe initial1
    c.cancel()

    effect shouldBe 0
  }
  
  test("alreadyCanceled returns same reference") {
    val ref1 = IOConnection.alreadyCanceled
    val ref2 = IOConnection.alreadyCanceled
    ref1 shouldBe ref2
  }

  test("alreadyCanceled reference is already cancelled") {
    val ref = IOConnection.alreadyCanceled
    ref.isCanceled shouldBe true
    ref.cancel()
    ref.isCanceled shouldBe true
  }

  test("alreadyCanceled.pop") {
    val ref = IOConnection.alreadyCanceled
    ref.pop() shouldBe Cancelable.dummy
  }

  test("alreadyCanceled.push cancels the given cancelable") {
    val ref = IOConnection.alreadyCanceled
    val c = BooleanCancelable()
    ref.push(c)
    c.isCanceled shouldBe true
  }
  
  test("uncancelable returns same reference") {
    val ref1 = IOConnection.uncancelable
    val ref2 = IOConnection.uncancelable
    ref1 shouldBe ref2
  }

  test("uncancelable reference cannot be cancelled") {
    val ref = IOConnection.uncancelable
    ref.isCanceled shouldBe false
    ref.cancel()
    ref.isCanceled shouldBe false
  }

  test("uncancelable.pop") {
    val ref = IOConnection.uncancelable
    ref.pop() shouldBe Cancelable.dummy
  }

  test("uncancelable.push never cancels the given cancelable") {
    val ref = IOConnection.uncancelable
    ref.cancel()

    val c = BooleanCancelable()
    ref.push(c)
    c.isCanceled shouldBe false
  }
}
