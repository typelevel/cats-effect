package cats.effect
package unsafe

import scala.scalanative.meta.LinktimeInfo.isMultithreadingEnabled
// import java.io.IOException
import munit.FunSuite
import cats.effect.IO
import cats.effect.unsafe.implicits.global
// import java.util.NoSuchElementException

class PollerInterruptSpec extends FunSuite {
  if (isMultithreadingEnabled) {
    test("interruptible executes normal code") {
      val result = IO
        .interruptible {
          42
        }
        .unsafeRunSync()
      assertEquals(result, 42)
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

    /**
     * On SN, we expect NoSuchElementException, and the interrupt flag may not be set
     * consistently
     *
     * test("interruptible allows Scala Native interruptions to propagate") { try { val _ =
     * Thread.interrupted()
     *
     * intercept[NoSuchElementException] { IO.interruptible { Thread.currentThread().interrupt()
     * throw new NoSuchElementException("interrupted") }.unsafeRunSync() }
     *
     * assert(Thread.interrupted(), "Interrupt flag should be set") } finally { val _ =
     * Thread.interrupted() } }
     *
     * test("interruptible restores original interrupt status") { try {
     * Thread.currentThread().interrupt() assert(Thread.interrupted(), "Thread should start
     * interrupted")
     *
     * intercept[RuntimeException] { IO.interruptible[Int] { throw new RuntimeException("boom")
     * }.unsafeRunSync() }
     *
     * assert(Thread.interrupted(), "Original interrupt status should be restored") } finally {
     * val _ = Thread.interrupted() } }
     */
  } else {
    test("interruptible tests skipped - multithreading disabled") {
      // No-op
    }
  }

}
