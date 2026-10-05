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

import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.duration._

class TimerStealBatchSuite extends BaseSuite {

  testUnit("run local work submitted by stolen timers before looking for more work") {
    val (compute, _, shutdown) = IORuntime.createWorkStealingComputeThreadPool(
      threads = 2,
      threadPrefix = "timer-steal-batch-test",
      pollingSystem = SleepSystem,
      shutdownTimeout = 1.second)
    val pool = compute.asInstanceOf[WorkStealingThreadPool[AnyRef]]
    val registered = new CountDownLatch(1)
    val releaseOwner = new CountDownLatch(1)
    val ownerFinished = new CountDownLatch(1)
    val callbacks = new CountDownLatch(512)
    val completed = new CountDownLatch(512)
    val callbackWorker = new AtomicReference[Thread]()
    val task: Runnable = () => completed.countDown()

    try {
      compute.execute(() => {
        try {
          var i = 0
          while (i < 512) {
            compute.sleep(
              1.second,
              () => {
                callbackWorker.set(Thread.currentThread())
                compute.execute(task)
                callbacks.countDown()
              })
            i += 1
          }
          registered.countDown()
          // Hold the timer owner so its peer must steal and invoke the callbacks.
          releaseOwner.await()
          ()
        } finally ownerFinished.countDown()
      })

      val ready = registered.await(5, TimeUnit.SECONDS)
      assert(ready, "worker did not register timers")
      Thread.sleep(1100)
      // Wake the peer after all timers have expired. It must enter lookForWork
      // to steal timers, rather than invoking package-private methods directly.
      compute.execute(() => ())
      val invoked = callbacks.await(5, TimeUnit.SECONDS)
      assert(invoked, s"pending timer callbacks=${callbacks.getCount()}")
      val finished = completed.await(2, TimeUnit.SECONDS)
      val stack = callbackWorker.get().getStackTrace.mkString("\n  ")
      assert(
        finished,
        s"Stolen timer tasks did not complete: pending=${completed.getCount()}\n  $stack")
    } finally {
      try {
        // On the buggy implementation, interruption cannot stop enqueueBatch's
        // spin. Concurrently steal from that queue to make room and release it.
        var i = 0
        while (i < 2) {
          val worker = pool.getWorkerThread(i)
          if (worker.getStackTrace.exists(_.getMethodName == "enqueueBatch")) {
            val rescue = new LocalQueue
            val first = pool.localQueues(i).stealInto(rescue, worker)
            if (first ne null) first.run()
            var next = rescue.dequeue(worker)
            while (next ne null) {
              next.run()
              next = rescue.dequeue(worker)
            }
          }
          i += 1
        }
      } finally {
        releaseOwner.countDown()
        ownerFinished.await(5, TimeUnit.SECONDS)
        shutdown()
      }
    }
  }
}
