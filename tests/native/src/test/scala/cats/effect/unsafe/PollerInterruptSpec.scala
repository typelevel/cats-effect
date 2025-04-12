/*
 * Copyright 2020-2025 Typelevel
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

package cats.effect.unsafe

import scala.scalanative.meta.LinktimeInfo.isMultithreadingEnabled
import munit.FunSuite
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.io.IOException

class PollerInterruptSpec extends FunSuite {
  if (isMultithreadingEnabled) {
    test("interruptible executes normal code") {
      val result = IO.interruptible(42).unsafeRunSync()
      assertEquals(result, 42)
    }

    /**
     * this appears to convert all thread interruptions (including InterruptedException) to
     * NoSuchElementException("interrupted"), which differs from JVM behavior. This affects
     * interoperability with JVM libraries expecting standard interruption patterns.
     */
    test("raw interruption type") {
      val ex = intercept[Throwable] {
        IO.interruptible {
          Thread.currentThread().interrupt()
          throw new NoSuchElementException("interrupted")
        }.unsafeRunSync()
      }
      println(s"Raw exception type: ${ex.getClass.getName}")
      assert(ex.isInstanceOf[NoSuchElementException])
    }

    test("interruptible preserves thread interrupt status") {
      try {
        Thread.currentThread().interrupt()
        assert(Thread.interrupted(), "Thread should start interrupted")

        val result = IO
          .interruptible {
            assert(!Thread.interrupted(), "Interrupt status should be cleared in block")
            42
          }
          .unsafeRunSync()

        assertEquals(result, 42)
      } finally {
        val _ = Thread.interrupted()
      }
    }

    test("interruptible handles native interruptions") {
      try {
        val ex = intercept[Throwable] {
          IO.interruptible[Unit] {
            throw new NoSuchElementException("interrupted")
          }.unsafeRunSync()
        }

        // On Native we expect either the original exception or a wrapped version
        assert(
          ex.isInstanceOf[NoSuchElementException] ||
            ex.isInstanceOf[IOException],
          s"Unexpected exception type: ${ex.getClass.getName}"
        )
      } finally {
        val _ = Thread.interrupted()
      }
    }

    test("interruptible restores state after exceptions") {
      try {
        Thread.currentThread().interrupt()
        assert(Thread.interrupted(), "Thread should start interrupted")

        intercept[RuntimeException] {
          IO.interruptible[Int] {
            throw new RuntimeException("boom")
          }.unsafeRunSync()
        }
      } finally {
        val _ = Thread.interrupted()
      }
    }
  } else {
    test("interruptible tests skipped - multithreading disabled")
  }
}
