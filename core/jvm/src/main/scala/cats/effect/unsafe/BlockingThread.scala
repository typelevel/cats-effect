/*
 * Copyright 2020-2021 Typelevel
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
package unsafe

import scala.concurrent.{BlockContext, CanAwait}

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

private final class BlockingThread(
    private[this] val threadPrefix: String,
    private[this] val blockingThreadNameCounter: AtomicInteger,
    private[this] val external: ConcurrentLinkedQueue[IOFiber[_]],
    private[this] val pool: WorkStealingThreadPool
) extends Thread
    with BlockContext {
  private[this] var signal: Int = _

  private[this] var blocked: Boolean = false

  private[this] val signalOffset: Long = {
    try {
      val field = classOf[BlockingThread].getDeclaredField("signal")
      Unsafe.objectFieldOffset(field)
    } catch {
      case t: Throwable =>
        throw new ExceptionInInitializerError(t)
    }
  }

  def setSignal(s: Int): Unit = {
    Unsafe.putOrderedInt(this, signalOffset, s)
  }

  def schedule(fiber: IOFiber[_]): Unit = {
    external.offer(fiber)
    ()
  }

  def reschedule(fiber: IOFiber[_]): Unit = {
    external.offer(fiber)
    ()
  }

  override def run(): Unit = {
    signal = 0
    while (!pool.done && Unsafe.getIntVolatile(this, signalOffset) == 0) {
      val fiber = external.poll()

      if (fiber == null) {
        return
      } else {
        fiber.run()
      }
    }
  }

  override def blockOn[T](thunk: => T)(implicit permission: CanAwait): T = {
    if (blocked) {
      thunk
    } else {
      blocked = true
      val helper = new BlockingThread(threadPrefix, blockingThreadNameCounter, external, pool)
      helper.setName(s"$threadPrefix-blocking-${blockingThreadNameCounter.incrementAndGet()}")
      helper.setDaemon(true)
      helper.start()
      val result = thunk
      helper.setSignal(1)
      helper.join()
      blocked = false
      result
    }
  }
}
