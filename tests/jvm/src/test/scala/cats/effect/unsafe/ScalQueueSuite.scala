/*
 * Copyright 2020-2024 Typelevel
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

import cats.effect.IOSuite

import java.util.concurrent.ThreadLocalRandom

class ScalQueueSuite extends IOSuite {

  /**
   * Tests that the ScalQueue metrics correctly track singleton and batch submissions.
   */
  test("ScalQueue metrics track singleton and batch counts") {
    // Create a queue with 4 stripes
    val queue = new ScalQueue(4)
    val random = ThreadLocalRandom.current()

    // Get initial metrics before any operations
    val initialSingletonCount = queue.getTotalSingletonCount()
    val initialBatchCount = queue.getTotalBatchCount()
    val initialFiberCount = queue.getTotalFiberCount()

    // Add a singleton task (a simple no-op Runnable)
    queue.offer(new Runnable { def run(): Unit = () }, random)

    // Verify that the singleton count has increased by exactly 1
    val afterSingletonCount = queue.getTotalSingletonCount()
    assertEquals(afterSingletonCount, initialSingletonCount + 1)

    // Create a batch of 10 no-op tasks
    val batchSize = 10
    val batch = new Array[Runnable](batchSize)
    var i = 0
    while (i < batchSize) {
      batch(i) = new Runnable { def run(): Unit = () }
      i += 1
    }

    // Add the batch to the queue
    queue.offerBatch(batch, random)

    // Verify that the batch count has increased
    val afterBatchCount = queue.getTotalBatchCount()
    assert(afterBatchCount > initialBatchCount)

    // Verify that the fiber count includes all tasks (singleton + batch)
    val afterFiberCount = queue.getTotalFiberCount()
    assert(afterFiberCount >= initialFiberCount + 1 + batchSize)

    // Test striping by adding several more singleton tasks
    var j = 0
    while (j < 4) {
      queue.offer(new Runnable { def run(): Unit = () }, random)
      j += 1
    }

    // Verify the updated singleton count
    val afterStripingSingletonCount = queue.getTotalSingletonCount()
    assertEquals(afterStripingSingletonCount, afterSingletonCount + 4)

    // Poll some tasks to verify they can be retrieved
    var polledCount = 0
    var element: AnyRef = null

    i = 0
    while (i < 10) {
      element = queue.poll(random)
      if (element ne null) {
        polledCount += 1

        // Execute the polled task
        if (element.isInstanceOf[Array[Runnable]]) {
          val taskArray = element.asInstanceOf[Array[Runnable]]
          var k = 0
          while (k < taskArray.length) {
            taskArray(k).run()
            k += 1
          }
        } else {
          element.asInstanceOf[Runnable].run()
        }
      }
      i += 1
    }

    // Verify we were able to poll at least one task
    assert(polledCount > 0)

    // Drain the queue completely
    element = queue.poll(random)
    while (element ne null) {
      // Execute the polled task
      if (element.isInstanceOf[Array[Runnable]]) {
        val taskArray = element.asInstanceOf[Array[Runnable]]
        var k = 0
        while (k < taskArray.length) {
          taskArray(k).run()
          k += 1
        }
      } else {
        element.asInstanceOf[Runnable].run()
      }

      // Get the next element
      element = queue.poll(random)
    }

    // Verify the queue is now empty
    assert(queue.isEmpty())
  }
}
