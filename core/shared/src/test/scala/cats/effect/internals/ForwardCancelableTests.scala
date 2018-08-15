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

import cats.effect.IO
import org.scalatest.{FunSuite, Matchers}

import scala.util.Success

class ForwardCancelableTests extends FunSuite with Matchers {
  test("cancel()") {
    var effect = 0
    val s = ForwardCancelable()
    val b = IO { effect += 1 }
    effect shouldBe 0

    s := b
    s.cancel.unsafeRunSync()
    effect shouldBe 1
    s.cancel.unsafeRunSync()
    effect shouldBe 1
  }

  test("cancel() (plus one)") {
    var effect = 0
    val extra = IO { effect += 1 }
    val b = IO { effect += 2 }

    val s = ForwardCancelable.plusOne(extra)
    s := b

    s.cancel.unsafeRunSync()
    effect shouldBe 3
    s.cancel.unsafeRunSync()
    effect shouldBe 3
  }

  test("cancel on single assignment") {
    val s = ForwardCancelable()
    s.cancel.unsafeRunAsyncAndForget()

    var effect = 0
    val b = IO { effect += 1 }
    s := b
    effect shouldBe 1

    s.cancel.unsafeRunSync()
    effect shouldBe 1
  }

  test("cancel on single assignment (plus one)") {
    var effect = 0
    val extra = IO { effect += 1 }
    val s = ForwardCancelable.plusOne(extra)

    s.cancel.unsafeRunAsyncAndForget()
    effect shouldBe 0

    val b = IO { effect += 1 }
    s := b

    effect shouldBe 2

    s.cancel.unsafeRunSync()
    effect shouldBe 2
  }

  test("throw exception on multi assignment") {
    val s = ForwardCancelable()
    s := IO.unit

    intercept[IllegalStateException] {
      s := IO.pure(())
    }
  }

  test("throw exception on multi assignment when canceled") {
    val s = ForwardCancelable()
    s.cancel.unsafeRunAsyncAndForget()
    s := IO.unit

    intercept[IllegalStateException] {
      s := IO.pure(())
    }
  }

  test("empty and cancelled reference back-pressures for assignment") {
    val s = ForwardCancelable()
    val f = s.cancel.unsafeToFuture()
    f.value shouldBe None

    s := IO.unit
    f.value shouldBe Some(Success(()))

    intercept[IllegalStateException] {
      s := IO.unit
    }
  }
}
