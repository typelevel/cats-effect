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

class ForwardCancelableTests extends FunSuite with Matchers {
  test("cancel()") {
    var effect = 0
    val s = ForwardCancelable()
    val b = BooleanCancelable { () => effect += 1 }

    s := b
    s()
    b.isCanceled shouldBe true
    effect shouldBe 1
    s()
    effect shouldBe 1
  }

  test("cancel() (plus one)") {
    var effect = 0
    val extra = BooleanCancelable { () => effect += 1 }
    val b = BooleanCancelable { () => effect += 2 }

    val s = ForwardCancelable.plusOne(extra)
    s := b

    s()
    b.isCanceled shouldBe true
    extra.isCanceled shouldBe true
    effect shouldBe 3
    s()
    effect shouldBe 3
  }

  test("cancel on single assignment") {
    val s = ForwardCancelable()
    s()

    var effect = 0
    val b = BooleanCancelable { () => effect += 1 }
    s := b

    b.isCanceled shouldBe true
    effect shouldBe 1

    s()
    effect shouldBe 1
  }

  test("cancel on single assignment (plus one)") {
    var effect = 0
    val extra = BooleanCancelable { () => effect += 1 }
    val s = ForwardCancelable.plusOne(extra)

    s()
    extra.isCanceled shouldBe true
    effect shouldBe 1

    val b = BooleanCancelable { () => effect += 1 }
    s := b

    b.isCanceled shouldBe true
    effect shouldBe 2

    s()
    effect shouldBe 2
  }

  test("throw exception on multi assignment") {
    val s = ForwardCancelable()
    val b1 = () => ()
    s := b1

    intercept[IllegalStateException] {
      val b2 = () => ()
      s := b2
    }
  }

  test("throw exception on multi assignment when canceled") {
    val s = ForwardCancelable()
    s()

    val b1 = () => ()
    s := b1

    intercept[IllegalStateException] {
      val b2 = () => ()
      s := b2
    }
  }
}
