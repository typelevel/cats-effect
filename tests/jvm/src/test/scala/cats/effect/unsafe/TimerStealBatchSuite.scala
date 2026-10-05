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

import cats.effect.BaseSuite

import java.util.concurrent.{
  CompletableFuture,
  CountDownLatch,
  ThreadLocalRandom,
  TimeUnit,
  TimeoutException
}
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.duration._

class TimerStealBatchSuite extends BaseSuite {

  testUnit("make room for an external batch after stolen timer callbacks fill the local queue") {
    val (compute, _, shutdown) = IORuntime.createWorkStealingComputeThreadPool(
      threads = 1,
      threadPrefix = "timer-steal-batch-test",
      pollingSystem = SleepSystem,
      shutdownTimeout = 1.second,
    )
    val pool = compute.asInstanceOf[WorkStealingThreadPool[AnyRef]]
    val reachedBatch = new CountDownLatch(1)
    val returned = new CompletableFuture[Unit]()
    val completed = new CountDownLatch(512)
    val callbacks = new AtomicInteger()
    val queueSize = new AtomicInteger()
    val task: Runnable = () => completed.countDown()

    try {
      compute.execute(() => {
        try {
          val worker = pool.getWorkerThread(0)
          val random = ThreadLocalRandom.current()
          val heap = pool.sleepers(0)
          val out = new Array[Right[Nothing, Unit] => Unit](1)
          val now = System.nanoTime()
          val delay = 1.second.toNanos
          val callback: Right[Nothing, Unit] => Unit = _ => {
            callbacks.incrementAndGet()
            compute.execute(task)
          }

          // Reproduce lookForWork's caller sequence on the owning worker. A synthetic
          // expiry time avoids depending on wall-clock scheduling of a timer burst.
          assertEquals(pool.localQueues(0).size(), 0)
          var i = 0
          while (i < 512) {
            heap.insert(now, delay, callback, out)
            i += 1
          }
          assert(pool.stealTimers(now + delay + 1, random))
          queueSize.set(pool.localQueues(0).size())
          reachedBatch.countDown()

          // Ordinary enqueue has spilled batches externally, but the local queue
          // is full again. This call must prepare capacity before enqueueBatch.
          val next = pool.stealFromOtherWorkerThread(0, random, worker)
          assert(next ne null)
          next.run()
          returned.complete(())
          ()
        } catch {
          case t: Throwable =>
            returned.completeExceptionally(t)
            reachedBatch.countDown()
        }
      })

      val reached = reachedBatch.await(5, TimeUnit.SECONDS)
      assert(
        reached,
        s"worker did not reach batch fallback: callbacks=${callbacks.get()}, " +
          pool.getWorkerThread(0).getStackTrace.mkString("\n  ")
      )
      assertEquals(callbacks.get(), 512)
      assertEquals(queueSize.get(), 256)
      try returned.get(2, TimeUnit.SECONDS)
      catch {
        case _: TimeoutException =>
          val stack = pool.getWorkerThread(0).getStackTrace.mkString("\n  ")
          fail(
            s"Batch fallback did not return: local queue size=${pool.localQueues(0).size()}, " +
              s"pending tasks=${completed.getCount()}\n  $stack")
      }
      assert(completed.await(5, TimeUnit.SECONDS), s"pending tasks=${completed.getCount()}")
    } finally {
      // enqueueBatch's spin ignores interruption. Rescue it through the supported
      // concurrent-stealer path so a failing test leaves no spinning daemon behind.
      try {
        if (!returned.isDone && reachedBatch.getCount() == 0) {
          val rescue = new LocalQueue
          val worker = pool.getWorkerThread(0)
          val first = pool.localQueues(0).stealInto(rescue, worker)
          if (first ne null) first.run()
          var next = rescue.dequeue(worker)
          while (next ne null) {
            next.run()
            next = rescue.dequeue(worker)
          }
          returned.get(5, TimeUnit.SECONDS)
          assert(completed.await(5, TimeUnit.SECONDS), "rescue did not complete queued tasks")
        }
      } finally shutdown()
    }
  }
}
