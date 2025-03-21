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

    // Verify initial metrics are all zero
    assertEquals(initialSingletonCount, 0L, "Initial singleton count should be zero")
    assertEquals(initialBatchCount, 0L, "Initial batch count should be zero")
    assertEquals(initialFiberCount, 0L, "Initial fiber count should be zero")

    // Add a singleton task (a simple no-op Runnable)
    queue.offer(new Runnable { def run(): Unit = () }, random)

    // Verify that the singleton count has increased to exactly 1
    val afterSingletonCount = queue.getTotalSingletonCount()
    assertEquals(afterSingletonCount, 1L, "Singleton count should be exactly 1")

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

    // Verify that the batch count has increased to exactly 1
    val afterBatchCount = queue.getTotalBatchCount()
    assertEquals(afterBatchCount, 1L, "Batch count should be exactly 1")

    // Verify that the fiber count includes all tasks (singleton + batch)
    val afterFiberCount = queue.getTotalFiberCount()
    assertEquals(
      afterFiberCount,
      1L + batchSize.toLong,
      "Fiber count should include singleton and batch tasks")

    // Test striping by adding several more singleton tasks
    var j = 0
    while (j < 4) {
      queue.offer(new Runnable { def run(): Unit = () }, random)
      j += 1
    }

    // Verify the updated singleton count is exactly 5 (1 initial + 4 more)
    val afterStripingSingletonCount = queue.getTotalSingletonCount()
    assertEquals(
      afterStripingSingletonCount,
      5L,
      "Singleton count should be exactly 5 after striping")

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
    assert(polledCount > 0, "Should have polled at least one task")

    // Check current in-queue metrics before final drain
    val currentSingletonCount = queue.getSingletonCount()
    val currentBatchCount = queue.getBatchCount()
    val currentFiberCount = queue.getFiberCount()

    // Note: Some tasks may have been polled already, so we only verify total metrics are higher
    assert(
      currentSingletonCount <= afterStripingSingletonCount,
      "Current singleton count should not exceed total singleton submissions")
    assert(
      currentBatchCount <= afterBatchCount,
      "Current batch count should not exceed total batch submissions")
    assert(
      currentFiberCount <= afterFiberCount + 4L,
      "Current fiber count should not exceed total fiber submissions")

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
    assert(queue.isEmpty(), "Queue should be empty after draining")

    // Verify present counts are now zero
    assertEquals(
      queue.getSingletonCount(),
      0L,
      "Singleton present count should be zero after draining")
    assertEquals(queue.getBatchCount(), 0L, "Batch present count should be zero after draining")
    assertEquals(queue.getFiberCount(), 0L, "Fiber present count should be zero after draining")
  }
}
