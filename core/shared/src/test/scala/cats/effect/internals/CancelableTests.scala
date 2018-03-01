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

import cats.effect.util.CompositeException
import org.scalatest.{FunSuite, Matchers}

class CancelableTests extends FunSuite with Matchers {
  test("dummy is a no-op") {
    Cancelable.dummy.apply()
    Cancelable.dummy.apply()
    Cancelable.dummy.apply()
  }

  test("cancelAll works for zero references") {
    Cancelable.cancelAll()
  }

  test("cancelAll works for one reference") {
    var wasCanceled = false
    Cancelable.cancelAll(() => { wasCanceled = true })
    wasCanceled shouldBe true
  }

  test("cancelAll catches error from one reference") {
    val dummy = new RuntimeException("dummy")
    var wasCanceled1 = false
    var wasCanceled2 = false

    try {
      Cancelable.cancelAll(
        () => { wasCanceled1 = true },
        () => { throw dummy },
        () => { wasCanceled2 = true }
      )
      fail("should have throw exception")
    } catch {
      case `dummy` =>
        wasCanceled1 shouldBe true
        wasCanceled2 shouldBe true
    }
  }

  test("cancelAll catches errors from two references") {
    val dummy1 = new RuntimeException("dummy1")
    val dummy2 = new RuntimeException("dummy2")
    var wasCanceled1 = false
    var wasCanceled2 = false

    try {
      Cancelable.cancelAll(
        () => { wasCanceled1 = true },
        () => { throw dummy1 },
        () => { throw dummy2 },
        () => { wasCanceled2 = true }
      )
      fail("should have throw exception")
    } catch {
      case CompositeException(`dummy1`, `dummy2`) =>
        wasCanceled1 shouldBe true
        wasCanceled2 shouldBe true
    }
  }
}
