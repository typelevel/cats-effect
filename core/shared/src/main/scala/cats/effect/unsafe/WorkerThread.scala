/*
 * Copyright 2020 Typelevel
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

import java.util.concurrent.locks.LockSupport

/**
 * Worker thread implementation used in WorkStealingThreadPool. Each worker thread has its own `WorkQueue` where
 * `IOFiber`s are scheduled for execution. This is the main difference to a fixed thread executor, in which all threads
 * contend for work from a single shared queue.
 */
private final class WorkerThread(
    private[unsafe] val index: Int,
    private[this] val pool: WorkStealingThreadPool)
    extends Thread {
  private[unsafe] val queue: WorkQueue = new WorkQueue()
  private[this] var waitSpinCount: Int = 0

  private[this] def stealFromLocalWorkQueue(): IOFiber[_] =
    queue.steal()

  override def run(): Unit = {
    var task: IOFiber[_] = null

    while (!pool.done) {
      task = stealFromLocalWorkQueue()
      if (task == null) {
        task = pool.stealFromExternalQueue()
      }
      if (task == null) {
        task = pool.stealFromOtherWorkQueue(index)
      }
      if (task == null) {
        if (!pool.done) {
          if (waitSpinCount < 5) {
            waitSpinCount += 1
            LockSupport.parkNanos(pool, 30000)
          } else {
            waitSpinCount = 0
            pool.park(this)
          }
        }
      } else {
        val fiber = task
        task = null
        fiber.queue = queue
        fiber.run()
      }
    }
  }
}
