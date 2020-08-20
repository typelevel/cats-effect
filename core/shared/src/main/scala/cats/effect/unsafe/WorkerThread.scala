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

/**
 * Worker thread implementation used in WorkStealingThreadPool. Each worker thread has its own `WorkQueue` where
 * `IOFiber`s are scheduled for execution. This is the main difference to a fixed thread executor, in which all threads
 * contend for work from a single shared queue.
 */
private final class WorkerThread(
    private[unsafe] val index: Int,
    private[this] val pool: WorkStealingThreadPool)
    extends Thread {
  private[effect] val queue: WorkQueue = new WorkQueue()

  private[this] def stealFromLocalWorkQueue(): IOFiber[_] =
    queue.steal()

  override def run(): Unit = {
    while (!pool.done) {
      var task: IOFiber[_] = stealFromLocalWorkQueue()
      if (task == null) {
        task = pool.stealFromExternalQueue()
      }
      if (task == null) {
        task = pool.stealFromOtherWorkQueue(index)
      }
      if (task == null) {
        pool.park(this)
      } else {
        task.queue = queue
        task.run()
      }
    }
  }
}
