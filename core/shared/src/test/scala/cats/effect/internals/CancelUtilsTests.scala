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

import java.io.ByteArrayOutputStream
import cats.effect.IO
import org.scalatest.matchers.should.Matchers
import org.scalatest.funsuite.AnyFunSuite
import scala.util.control.NonFatal

class CancelUtilsTests extends AnyFunSuite with Matchers with TestUtils {
  test("cancelAll works for zero references") {
    CancelUtils.cancelAll().unsafeRunSync()
  }

  test("cancelAll works for one reference") {
    var wasCanceled = false
    CancelUtils.cancelAll(IO { wasCanceled = true }).unsafeRunSync()
    wasCanceled shouldBe true
  }

  test("cancelAll catches error from one reference") {
    val dummy = new RuntimeException("dummy")
    var wasCanceled1 = false
    var wasCanceled2 = false

    val io = CancelUtils.cancelAll(
      IO { wasCanceled1 = true },
      IO(throw dummy),
      IO { wasCanceled2 = true }
    )

    try {
      io.unsafeRunSync()
      fail("should have throw exception")
    } catch {
      case `dummy` =>
        wasCanceled1 shouldBe true
        wasCanceled2 shouldBe true
    }
  }

  test("cancelAll catches the first error and logs the rest") {
    val dummy1 = new RuntimeException("dummy1")
    val dummy2 = new RuntimeException("dummy2")
    var wasCanceled1 = false
    var wasCanceled2 = false

    val io = CancelUtils.cancelAll(
      IO { wasCanceled1 = true },
      IO(throw dummy1),
      IO(throw dummy2),
      IO { wasCanceled2 = true }
    )

    val sysErr = new ByteArrayOutputStream()
    try {
      catchSystemErrInto(sysErr) {
        io.unsafeRunSync()
      }
      fail("should have throw exception")
    } catch {
      case NonFatal(error) =>
        error shouldBe dummy1
        sysErr.toString("utf-8") should include("dummy2")
        dummy1.getSuppressed shouldBe empty // ensure memory isn't leaked with addSuppressed
        dummy2.getSuppressed shouldBe empty // ensure memory isn't leaked with addSuppressed
    }
  }
}
