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

import cats.effect.{IO, IOSuite}
import cats.effect.unsafe.{IORuntime, IORuntimeConfig}

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._

class ScalQueueSuite extends IOSuite {

  test("ScalQueue metrics track singleton submissions and batch overflows") {
    // Create a single-threaded runtime directly to make testing deterministic
    val test = IO {
      val (pool, _, shutdown) = IORuntime.createWorkStealingComputeThreadPool(threads = 1)
      val runtime = IORuntime
        .builder()
        .setConfig(
          IORuntimeConfig(
            cancelationCheckThreshold = 1,
            autoYieldThreshold = 2
          ))
        .setCompute(pool, shutdown)
        .build()

      try {
        // Setup test coordination
        val completionLatch = new CountDownLatch(1)
        val singletonLatch = new CountDownLatch(1)
        val testResult = new AtomicReference[Either[Throwable, Unit]](null)

        val compute = runtime.compute
        val wstp = compute.asInstanceOf[WorkStealingThreadPool[?]]
        val queue = wstp.externalQueue

        // Get initial metrics
        val initialTotalSingletonCount = queue.getTotalSingletonCount()
        val initialTotalBatchCount = queue.getTotalBatchCount()
        val initialTotalFiberCount = queue.getTotalFiberCount()

        // Submit singleton task
        compute.execute(() => {
          try {
            // Signal that we've started executing
            singletonLatch.countDown()

            // Get metrics after singleton execution started
            val afterSingletonTotalCount = queue.getTotalSingletonCount()

            // Verify singleton metrics
            assert(
              afterSingletonTotalCount == initialTotalSingletonCount + 1,
              s"Expected total singleton count to increase by 1, but was $afterSingletonTotalCount (initial: $initialTotalSingletonCount)"
            )

            // Now create enough tasks to overflow the local queue (capacity is 256)
            val manyTasks = (0 until 257).map { i =>
              new Runnable {
                def run(): Unit = {
                  // Just do a small computation
                  val _ = i * i
                }
              }
            }.toArray

            // Submit all tasks - this will overflow the local queue
            manyTasks.foreach(compute.execute)

            // Give the runtime a chance to process the batch
            Thread.sleep(50)

            // Get metrics after batch submission
            val afterBatchTotalCount = queue.getTotalBatchCount()
            val afterFiberTotalCount = queue.getTotalFiberCount()

            // Verify batch metrics
            assert(
              afterBatchTotalCount > initialTotalBatchCount,
              s"Expected total batch count to increase, but was $afterBatchTotalCount (initial: $initialTotalBatchCount)"
            )

            // Verify fiber count includes both singleton and batch tasks
            assert(
              afterFiberTotalCount >= initialTotalFiberCount + 1 + 257,
              s"Expected total fiber count to increase by at least 258, but was $afterFiberTotalCount (initial: $initialTotalFiberCount)"
            )

            // Test succeeded
            testResult.set(Right(()))
          } catch {
            case t: Throwable =>
              testResult.set(Left(t))
          } finally {
            // Signal that test is complete
            completionLatch.countDown()
          }
        })

        // Wait for the singleton task to start executing
        if (!singletonLatch.await(1, java.util.concurrent.TimeUnit.SECONDS)) {
          throw new RuntimeException("Timed out waiting for singleton task to execute")
        }

        // Wait for the test to complete
        if (!completionLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
          throw new RuntimeException("Timed out waiting for test to complete")
        }

        // Propagate any errors from the test
        Option(testResult.get()).foreach {
          case Right(_) => ()
          case Left(t) => throw t
        }
      } finally {
        runtime.shutdown()
      }
    }

    test
  }
}
