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

/*
 * This code is an adaptation of the `worker` and `idle` code from the `tokio` runtime.
 * The original source code in Rust is licensed under the MIT license and available
 * at: https://docs.rs/crate/tokio/0.2.22/source/src/runtime/thread_pool/worker.rs and
 * https://docs.rs/crate/tokio/0.2.22/source/src/runtime/thread_pool/idle.rs.
 *
 * For the reasoning behind the design decisions of this code, please consult:
 * https://tokio.rs/blog/2019-10-scheduler.
 */

package cats.effect
package unsafe

import scala.concurrent.ExecutionContext

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/**
 * Work-stealing thread pool which manages a pool of `WorkerThread`s for the specific purpose of executing `IOFiber`s
 * with work-stealing semantics.
 *
 * The thread pool starts with `threadCount` worker threads in the active state, looking to find fibers to
 * execute in their own local work stealing queues, or externally scheduled work coming from the external queue.
 *
 * In the case that a worker thread cannot find work to execute on its own queue, or the external queue,
 * it asks for permission from the pool to enter the searching state, which allows that thread to try
 * and steal work from the other worker threads. The pool tries to maintain that at most `threadCount / 2`
 * worker threads are searching for work, to reduce contention. Work stealing is tried linearly starting from
 * a random worker thread.
 */
private[effect] final class WorkStealingThreadPool(
    threadCount: Int, // number of worker threads
    threadPrefix: String, // prefix for the name of worker threads
    self0: => IORuntime
) extends WorkStealingThreadPool.Padding
    with ExecutionContext {

  import WorkStealingThreadPoolConstants._

  // Used to implement the `scala.concurrent.ExecutionContext` interface, for suspending
  // `java.lang.Runnable` instances into `IOFiber`s.
  private[this] lazy val self: IORuntime = self0

  // References to the worker threads.
  private[this] val workers: Array[WorkerThread] = new Array(threadCount)

  // The external queue on which fibers coming from outside the pool are enqueued, or acts
  // as a place where spillover work from other local queues can go.
  private[this] val externalQueue: ConcurrentLinkedQueue[IOFiber[_]] =
    new ConcurrentLinkedQueue()

  // Shutdown signal for the worker threads.
  @volatile var done: Boolean = false

  private[this] val blockingThreadNameCounter: AtomicInteger = new AtomicInteger()

  private[this] val stateOffset: Long = {
    try {
      val field = classOf[WorkStealingThreadPool.State].getDeclaredField("state")
      Unsafe.objectFieldOffset(field)
    } catch {
      case t: Throwable =>
        throw new ExceptionInInitializerError(t)
    }
  }

  // Initialization block.
  {
    state = threadCount << UnparkShift
    // Set up the worker threads.
    var i = 0
    while (i < threadCount) {
      val index = i
      val thread =
        new WorkerThread(
          index,
          threadCount,
          threadPrefix,
          blockingThreadNameCounter,
          workers,
          externalQueue,
          stateOffset,
          this)
      thread.setName(s"$threadPrefix-$index")
      thread.setDaemon(true)
      workers(i) = thread
      i += 1
    }

    // Start the worker threads.
    i = 0
    while (i < threadCount) {
      workers(i).start()
      i += 1
    }
  }

  /**
   * Tries rescheduling the fiber directly on the local work stealing queue, if executed from
   * a worker thread. Otherwise falls back to scheduling on the external queue.
   */
  def executeFiber(fiber: IOFiber[_]): Unit = {
    if (Thread.currentThread().isInstanceOf[WorkerThread]) {
      scheduleFiber(fiber)
    } else {
      externalQueue.offer(fiber)
      val st = Unsafe.getIntVolatile(this, stateOffset)
      if ((st & SearchMask) == 0 && ((st & UnparkMask) >>> UnparkShift) < threadCount) {
        val from = scala.util.Random.nextInt(threadCount)
        var i = 0
        while (i < threadCount) {
          val idx = (from + i) % threadCount
          val worker = workers(idx)
          if (worker.isSleeping() && worker.tryWakeUp()) {
            Unsafe.getAndAddInt(this, stateOffset, (1 << UnparkShift) | 1)
            LockSupport.unpark(worker)
            return
          }
          i += 1
        }
      }
    }
  }

  /**
   * Reschedules the given fiber directly on the local work stealing queue on the same thread,
   * but with the possibility to skip notifying other fibers of a potential steal target, which
   * reduces contention in workloads running on fewer worker threads. This method executes an
   * unchecked cast to a `WorkerThread` and should only ever be called directly from a
   * `WorkerThread`.
   */
  def rescheduleFiber(fiber: IOFiber[_]): Unit = {
    if (Thread.currentThread().isInstanceOf[WorkerThread]) {
      Thread.currentThread().asInstanceOf[WorkerThread].reschedule(fiber)
    } else {
      Thread.currentThread().asInstanceOf[BlockingThread].reschedule(fiber)
    }
  }

  /**
   * Reschedules the given fiber directly on the local work stealing queue on the same thread.
   * This method executes an unchecked cast to a `WorkerThread` and should only ever be called
   * directly from a `WorkerThread`.
   */
  def scheduleFiber(fiber: IOFiber[_]): Unit = {
    if (Thread.currentThread().isInstanceOf[WorkerThread]) {
      Thread.currentThread().asInstanceOf[WorkerThread].schedule(fiber)
    } else {
      Thread.currentThread().asInstanceOf[BlockingThread].schedule(fiber)
    }
  }

  /**
   * Schedule a `java.lang.Runnable` for execution in this thread pool. The runnable
   * is suspended in an `IO` and executed as a fiber.
   */
  def execute(runnable: Runnable): Unit = {
    if (runnable.isInstanceOf[IOFiber[_]]) {
      executeFiber(runnable.asInstanceOf[IOFiber[_]])
    } else {
      // It's enough to only do this check here as there is no other way to submit work to the `ExecutionContext`
      // represented by this thread pool after it has been shutdown. Also, no one else can create raw fibers
      // directly, as `IOFiber` is not a public type.
      if (done) {
        return
      }

      // `unsafeRunFiber(true)` will enqueue the fiber, no need to do it manually
      IO(runnable.run()).unsafeRunFiber((), reportFailure, _ => ())(self)
      ()
    }
  }

  def reportFailure(cause: Throwable): Unit = {
    cause.printStackTrace()
  }

  /**
   * Shutdown the thread pool. Calling this method after the pool has been shut down
   * has no effect.
   */
  def shutdown(): Unit = {
    if (done) {
      return
    }

    // Set the worker thread shutdown flag.
    done = true
    // Shutdown and drain the external queue.
    externalQueue.clear()
    // Send an interrupt signal to each of the worker threads.
    workers.foreach(_.interrupt())
    // Remove the references to the worker threads so that they can be cleaned up, including their worker queues.
    for (i <- 0 until workers.length) {
      workers(i) = null
    }
  }
}

private[effect] object WorkStealingThreadPool {
  abstract class InitPadding {
    protected val pinit00: Long = 0
    protected val pinit01: Long = 0
    protected val pinit02: Long = 0
    protected val pinit03: Long = 0
    protected val pinit04: Long = 0
    protected val pinit05: Long = 0
    protected val pinit06: Long = 0
    protected val pinit07: Long = 0
    protected val pinit08: Long = 0
    protected val pinit09: Long = 0
    protected val pinit10: Long = 0
    protected val pinit11: Long = 0
    protected val pinit12: Long = 0
    protected val pinit13: Long = 0
    protected val pinit14: Long = 0
    protected val pinit15: Long = 0
  }

  abstract class State extends InitPadding {
    @volatile protected var state: Int = 0
  }

  abstract class Padding extends State {
    protected val pwstp00: Long = 0
    protected val pwstp01: Long = 0
    protected val pwstp02: Long = 0
    protected val pwstp03: Long = 0
    protected val pwstp04: Long = 0
    protected val pwstp05: Long = 0
    protected val pwstp06: Long = 0
    protected val pwstp07: Long = 0
    protected val pwstp08: Long = 0
    protected val pwstp09: Long = 0
    protected val pwstp10: Long = 0
    protected val pwstp11: Long = 0
    protected val pwstp12: Long = 0
    protected val pwstp13: Long = 0
    protected val pwstp14: Long = 0
    protected val pwstp15: Long = 0
  }
}
