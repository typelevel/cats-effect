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

/*
 * The pool management code in this class is heavily inspired by the `worker`
 * and `idle` code from the `tokio` runtime. The original source code in Rust is
 * licensed under the MIT license and available at:
 * https://docs.rs/crate/tokio/0.2.22/source/src/runtime/thread_pool/worker.rs
 * and
 * https://docs.rs/crate/tokio/0.2.22/source/src/runtime/thread_pool/idle.rs.
 *
 * For the design decisions behind the `tokio` runtime, please consult the
 * following resource:
 * https://tokio.rs/blog/2019-10-scheduler.
 */

package cats.effect
package unsafe

import cats.effect.tracing.Tracing.captureTrace
import cats.effect.tracing.TracingConstants

import scala.collection.mutable
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.control.NonFatal

import java.time.Instant
import java.time.temporal.ChronoField
import java.util.Comparator
import java.util.concurrent.{ConcurrentSkipListSet, ThreadLocalRandom}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}

import WorkStealingThreadPool._

/**
 * Work-stealing thread pool which manages a pool of [[WorkerThread]] s for the specific purpose
 * of executing [[java.lang.Runnable]] instancess with work-stealing scheduling semantics.
 *
 * The thread pool starts with `threadCount` worker threads in the active state, looking to find
 * fibers to execute in their own local work stealing queues, or externally scheduled work
 * coming from the external queue.
 *
 * In the case that a worker thread cannot find work to execute in its own queue, or the
 * external queue, it asks for permission from the pool to enter the searching state, which
 * allows that thread to try and steal work from the other worker threads. The pool tries to
 * maintain at most `threadCount / 2` worker threads which are searching for work, to reduce
 * contention. Work stealing is tried using a linear search starting from a random worker thread
 * index.
 */
private[effect] final class WorkStealingThreadPool[P](
    threadCount: Int, // number of worker threads
    private[unsafe] val threadPrefix: String, // prefix for the name of worker threads
    private[unsafe] val blockerThreadPrefix: String, // prefix for the name of worker threads currently in a blocking region
    private[unsafe] val runtimeBlockingExpiration: Duration,
    private[unsafe] val blockedThreadDetectionEnabled: Boolean,
    shutdownTimeout: Duration,
    system: PollingSystem.WithPoller[P],
    reportFailure0: Throwable => Unit
) extends ExecutionContextExecutor
    with Scheduler {

  import TracingConstants._
  import WorkStealingThreadPoolConstants._

  /**
   * References to worker threads and their local queues.
   */
  private[this] val workerThreads: Array[WorkerThread[P]] = new Array(threadCount)
  private[unsafe] val localQueues: Array[LocalQueue] = new Array(threadCount)
  private[unsafe] val sleepers: Array[TimerHeap] = new Array(threadCount)
  private[unsafe] val parkedSignals: Array[AtomicBoolean] = new Array(threadCount)
  private[unsafe] val fiberBags: Array[WeakBag[Runnable]] = new Array(threadCount)
  private[unsafe] val pollers: Array[P] =
    new Array[AnyRef](threadCount).asInstanceOf[Array[P]]

  private[unsafe] def accessPoller(cb: P => Unit): Unit = {

    // figure out where we are
    val thread = Thread.currentThread()
    val pool = WorkStealingThreadPool.this
    if (thread.isInstanceOf[WorkerThread[_]]) {
      val worker = thread.asInstanceOf[WorkerThread[P]]
      if (worker.isOwnedBy(pool)) // we're good
        cb(worker.poller())
      else // possibly a blocking worker thread, possibly on another wstp
        scheduleExternal(() => accessPoller(cb))
    } else scheduleExternal(() => accessPoller(cb))
  }

  /**
   * Atomic variable for used for publishing changes to the references in the `workerThreads`
   * array. Worker threads can be changed whenever blocking code is encountered on the pool.
   * When a worker thread is about to block, it spawns a new worker thread that would replace
   * it, transfers the local queue to it and proceeds to run the blocking code, after which it
   * exits.
   */
  private[this] val workerThreadPublisher: AtomicBoolean = new AtomicBoolean(false)

  private[this] val externalQueue: ScalQueue[AnyRef] =
    new ScalQueue(threadCount << 2)

  /**
   * Represents two unsigned 16 bit integers. The 16 most significant bits track the number of
   * active (unparked) worker threads. The 16 least significant bits track the number of worker
   * threads that are searching for work to steal from other worker threads.
   */
  private[this] val state: AtomicInteger = new AtomicInteger(threadCount << UnparkShift)

  private[unsafe] val cachedThreads: ConcurrentSkipListSet[WorkerThread[P]] =
    new ConcurrentSkipListSet(Comparator.comparingInt[WorkerThread[P]](_.nameIndex))

  /**
   * The shutdown latch of the work stealing thread pool.
   */
  private[unsafe] val done: AtomicBoolean = new AtomicBoolean(false)

  private[unsafe] val blockedWorkerThreadCounter: AtomicInteger = new AtomicInteger(0)
  private[unsafe] val blockedWorkerThreadNamingIndex: AtomicInteger = new AtomicInteger(0)

  // Thread pool initialization block.
  {
    // Set up the worker threads.
    var i = 0
    while (i < threadCount) {
      val queue = new LocalQueue()
      localQueues(i) = queue
      val sleepersHeap = new TimerHeap()
      sleepers(i) = sleepersHeap
      val parkedSignal = new AtomicBoolean(false)
      parkedSignals(i) = parkedSignal
      val index = i
      val fiberBag = new WeakBag[Runnable]()
      fiberBags(i) = fiberBag
      val poller = system.makePoller()
      pollers(i) = poller

      val thread =
        new WorkerThread(
          index,
          queue,
          parkedSignal,
          externalQueue,
          fiberBag,
          sleepersHeap,
          system,
          poller,
          this)

      workerThreads(i) = thread
      i += 1
    }

    // Publish the worker threads.
    workerThreadPublisher.set(true)

    // Start the worker threads.
    i = 0
    while (i < threadCount) {
      workerThreads(i).start()
      i += 1
    }
  }

  private[unsafe] def getWorkerThreads: Array[WorkerThread[P]] = workerThreads

  /**
   * Tries to steal work from other worker threads. This method does a linear search of the
   * worker threads starting at a random index. If the stealing attempt was unsuccessful, this
   * method falls back to checking the external queue.
   *
   * @param dest
   *   the index of the worker thread attempting to steal work from other worker threads (used
   *   to avoid stealing from its own local queue)
   * @param random
   *   a reference to an uncontended source of randomness, to be passed along to the striped
   *   concurrent queues when executing their enqueue operations
   * @param destWorker
   *   a reference to the destination worker thread, used for setting the active fiber reference
   * @return
   *   a fiber instance to execute instantly in case of a successful steal
   */
  private[unsafe] def stealFromOtherWorkerThread(
      dest: Int,
      random: ThreadLocalRandom,
      destWorker: WorkerThread[P]): Runnable = {
    val destQueue = localQueues(dest)
    val from = random.nextInt(threadCount)

    var i = 0
    while (i < threadCount) {
      // Compute the index of the thread to steal from.
      val index = (from + i) % threadCount

      if (index != dest) {
        // Do not steal from yourself.
        val res = localQueues(index).stealInto(destQueue, destWorker)
        if (res != null) {
          // Successful steal. Return the next fiber to be executed.
          return res
        }
      }

      i += 1
    }

    // The worker thread could not steal any work. Fall back to checking the
    // external queue.
    val element = externalQueue.poll(random)
    if (element.isInstanceOf[Array[Runnable]]) {
      val batch = element.asInstanceOf[Array[Runnable]]
      destQueue.enqueueBatch(batch, destWorker)
    } else if (element.isInstanceOf[Runnable]) {
      val fiber = element.asInstanceOf[Runnable]

      if (isStackTracing) {
        destWorker.active = fiber
        parkedSignals(dest).lazySet(false)
      }

      fiber
    } else {
      null
    }
  }

  /**
   * Tries to invoke the expired timers of some (possibly) other `WorkerThread`. After
   * successfully stealing the timers of a thread, it returns (i.e., it doesn't try to steal
   * from ALL threads).
   *
   * @param now
   *   the current time as returned by `System.nanoTime`
   * @param random
   *   the `ThreadLocalRandom` of the current thread
   * @return
   *   whether stealing was successful
   */
  private[unsafe] def stealTimers(now: Long, random: ThreadLocalRandom): Boolean = {
    val from = random.nextInt(threadCount)
    var i = 0
    while (i < threadCount) {
      // Compute the index of the thread to steal from
      // (note: it doesn't matter if we try to steal
      // from ourselves).
      val index = (from + i) % threadCount
      val invoked = sleepers(index).steal(now) // whether we successfully invoked a timer

      if (invoked) {
        // we did some work, don't
        // check other threads
        return true
      } else {
        i += 1
      }
    }

    false
  }

  /**
   * Potentially unparks a worker thread.
   *
   * @param random
   *   a reference to an uncontended source of randomness, to be passed along to the striped
   *   concurrent queues when executing their enqueue operations
   */
  private[unsafe] def notifyParked(random: ThreadLocalRandom): Boolean = {
    // Find a worker thread to unpark.
    if (!notifyShouldWakeup()) {
      // There are enough searching and/or running worker threads.
      // Unparking a new thread would probably result in unnecessary contention.
      return false
    }

    // Unpark a worker thread.
    val from = random.nextInt(threadCount)
    var i = 0
    while (i < threadCount) {
      val index = (from + i) % threadCount

      val signal = parkedSignals(index)
      if (signal.getAndSet(false)) {
        // Update the state so that a thread can be unparked.
        // Here we are updating the 16 most significant bits, which hold the
        // number of active threads, as well as incrementing the number of
        // searching worker threads (unparked worker threads are implicitly
        // allowed to search for work in the local queues of other worker
        // threads).
        state.getAndAdd(DeltaSearching)
        // Fetch the latest references to the worker threads before doing the
        // actual unparking. There is no danger of a race condition where the
        // parked signal has been successfully marked as unparked but the
        // underlying worker thread reference has changed, because a worker thread
        // can only be replaced right before executing blocking code, at which
        // point it is already unparked and entering this code region is thus
        // impossible.
        workerThreadPublisher.get()
        val worker = workerThreads(index)
        system.interrupt(worker, pollers(index))
        return true
      }

      i += 1
    }

    false
  }

  /**
   * Checks the number of active and searching worker threads and decides whether another thread
   * should be notified of new work.
   *
   * Should wake up another worker thread when there are 0 searching threads and fewer than
   * `threadCount` active threads.
   *
   * @return
   *   `true` if a parked worker thread should be woken up, `false` otherwise
   */
  private[this] def notifyShouldWakeup(): Boolean = {
    val st = state.get()
    (st & SearchMask) == 0 && ((st & UnparkMask) >>> UnparkShift) < threadCount
  }

  /**
   * Notifies a thread if there are fibers available for stealing in any of the local queues, or
   * in the external queue.
   *
   * @param random
   *   a reference to an uncontended source of randomness, to be passed along to the striped
   *   concurrent queues when executing their enqueue operations
   */
  private[unsafe] def notifyIfWorkPending(random: ThreadLocalRandom): Unit = {
    var i = 0
    while (i < threadCount) {
      // Check each worker thread for available work that can be stolen.
      if (localQueues(i).nonEmpty()) {
        notifyParked(random)
        return
      }
      i += 1
    }

    // If no work was found in the local queues of the worker threads, look for
    // work in the external queue.
    if (externalQueue.nonEmpty()) {
      notifyParked(random)
      ()
    }
  }

  /**
   * Decides whether a worker thread is allowed to enter the searching state where it can look
   * for work to steal from other worker threads.
   *
   * @return
   *   `true` if the requesting worker thread should be allowed to search for work to steal from
   *   other worker threads, `false` otherwise
   */
  private[unsafe] def transitionWorkerToSearching(): Boolean = {
    val st = state.get()

    // Try to keep at most around 50% threads that are searching for work, to
    // reduce unnecessary contention. It is not exactly 50%, but it is a good
    // enough approximation.
    if (2 * (st & SearchMask) >= threadCount) {
      // There are enough searching worker threads. Do not allow this thread to
      // enter the searching state.
      false
    } else {
      // Allow this thread to enter the searching state (increment the number of
      // searching threads).
      state.getAndIncrement()
      true
    }
  }

  /**
   * Deregisters the current worker thread from the set of searching threads and asks for help
   * with the local queue if necessary.
   *
   * @param random
   *   a reference to an uncontended source of randomness, to be passed along to the striped
   *   concurrent queues when executing their enqueue operations
   */
  private[unsafe] def transitionWorkerFromSearching(random: ThreadLocalRandom): Unit = {
    // Decrement the number of searching worker threads.
    val prev = state.getAndDecrement()
    if (prev == 1) {
      // If this was the only searching thread, wake a thread up to potentially help out
      // with the local work queue.
      notifyParked(random)
      ()
    }
  }

  /**
   * Updates the internal state to mark the given worker thread as parked.
   *
   * @return
   *   `true` if the given worker thread was the last searching thread, `false` otherwise
   */
  private[unsafe] def transitionWorkerToParkedWhenSearching(): Boolean = {
    // Decrement the number of unparked and searching threads simultaneously
    // since this thread was searching prior to parking.
    val prev = state.getAndAdd(-DeltaSearching)
    (prev & SearchMask) == 1
  }

  /**
   * Updates the internal state to mark the given worker thread as parked.
   *
   * @note
   *   This method is intentionally duplicated, to accommodate the unconditional code paths in
   *   the [[WorkerThread]] runloop.
   */
  private[unsafe] def transitionWorkerToParked(): Unit = {
    // Decrement the number of unparked threads only.
    state.getAndAdd(-DeltaNotSearching)
    ()
  }

  private[unsafe] def doneSleeping(): Unit = {
    state.getAndAdd(DeltaSearching)
    ()
  }

  /**
   * Replaces the blocked worker thread with the provided index with a clone.
   *
   * @param index
   *   the worker thread index at which the replacement will take place
   * @param newWorker
   *   the new worker thread instance to be installed at the provided index
   */
  private[unsafe] def replaceWorker(index: Int, newWorker: WorkerThread[P]): Unit = {
    workerThreads(index) = newWorker
    workerThreadPublisher.lazySet(true)
  }

  /**
   * Rechedules a [[java.lang.Runnable]] on this thread pool.
   *
   * If the request comes from a [[WorkerThread]], depending on the current load, the task can
   * be scheduled for immediate execution on the worker thread, potentially bypassing the local
   * queue and reducing the stealing pressure.
   *
   * If the request comes from a [[HelperTread]] or an external thread, the fiber is enqueued on
   * the external queue. Furthermore, if the request comes from an external thread, worker
   * threads are notified of new work.
   *
   * @param runnable
   *   the runnable to be executed on the thread pool
   */
  private[effect] def reschedule(runnable: Runnable): Unit = {
    val pool = this
    val thread = Thread.currentThread()

    if (thread.isInstanceOf[WorkerThread[_]]) {
      val worker = thread.asInstanceOf[WorkerThread[P]]
      if (worker.isOwnedBy(pool)) {
        worker.reschedule(runnable)
      } else {
        scheduleExternal(runnable)
      }
    } else {
      scheduleExternal(runnable)
    }
  }

  /**
   * Checks if the blocking code can be executed in the current context (only returns true for
   * worker threads that belong to this execution context).
   */
  private[effect] def canExecuteBlockingCode(): Boolean = {
    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerThread[_]]) {
      val worker = thread.asInstanceOf[WorkerThread[P]]
      worker.canExecuteBlockingCodeOn(this)
    } else {
      false
    }
  }

  /**
   * Prepares the current thread for running blocking code. This should be called only if
   * [[canExecuteBlockingCode]] returns `true`.
   */
  private[effect] def prepareForBlocking(): Unit = {
    val thread = Thread.currentThread()
    val worker = thread.asInstanceOf[WorkerThread[_]]
    worker.prepareForBlocking()
  }

  /**
   * Schedules a fiber for execution on this thread pool originating from an external thread (a
   * thread which is not owned by this thread pool).
   *
   * @param fiber
   *   the fiber to be executed on the thread pool
   */
  private[this] def scheduleExternal(fiber: Runnable): Unit = {
    val random = ThreadLocalRandom.current()
    externalQueue.offer(fiber, random)
    notifyParked(random)
    ()
  }

  /**
   * Returns a snapshot of the fibers currently live on this thread pool.
   *
   * @return
   *   a 3-tuple consisting of the set of fibers on the external queue, a map associating worker
   *   threads to the currently active fiber and fibers enqueued on the local queue of that
   *   worker thread and a set of suspended fibers tracked by this thread pool
   */
  private[unsafe] def liveTraces(): (
      Map[Runnable, Trace],
      Map[WorkerThread[P], (Thread.State, Option[(Runnable, Trace)], Map[Runnable, Trace])],
      Map[Runnable, Trace]) = {
    val externalFibers: Map[Runnable, Trace] = externalQueue
      .snapshot()
      .iterator
      .flatMap {
        case batch: Array[Runnable] =>
          batch.flatMap(r => captureTrace(r)).toMap[Runnable, Trace]
        case r: Runnable => captureTrace(r).toMap[Runnable, Trace]
        case _ => Map.empty[Runnable, Trace]
      }
      .toMap

    val map = mutable
      .Map
      .empty[WorkerThread[P], (Thread.State, Option[(Runnable, Trace)], Map[Runnable, Trace])]
    val suspended = mutable.Map.empty[Runnable, Trace]

    var i = 0
    while (i < threadCount) {
      val localFibers = localQueues(i).snapshot().iterator.flatMap(r => captureTrace(r)).toMap
      val worker = workerThreads(i)
      val _ = parkedSignals(i).get()
      val active = Option(worker.active)
      map += (worker -> ((
        worker.getState(),
        active.flatMap(a => captureTrace(a)),
        localFibers)))
      suspended ++= worker.suspendedTraces()
      i += 1
    }

    (externalFibers, map.toMap, suspended.toMap)
  }

  /**
   * Executes a [[java.lang.Runnable]] on the [[WorkStealingThreadPool]].
   *
   * If the request comes from a [[WorkerThread]], the task is enqueued on the local queue of
   * that thread.
   *
   * If the request comes from a [[HelperTread]] or an external thread, the task is enqueued on
   * the external queue. Furthermore, if the request comes from an external thread, worker
   * threads are notified of new work.
   *
   * This method fulfills the `ExecutionContext` interface.
   *
   * @param runnable
   *   the runnable to be executed
   */
  override def execute(runnable: Runnable): Unit = {
    val pool = this
    val thread = Thread.currentThread()

    if (thread.isInstanceOf[WorkerThread[_]]) {
      val worker = thread.asInstanceOf[WorkerThread[P]]
      if (worker.isOwnedBy(pool)) {
        worker.schedule(runnable)
      } else {
        scheduleExternal(runnable)
      }
    } else {
      scheduleExternal(runnable)
    }
  }

  /**
   * Reports unhandled exceptions and errors according to the configured handler.
   *
   * This method fulfills the `ExecutionContext` interface.
   *
   * @param cause
   *   the unhandled throwable instances
   */
  override def reportFailure(cause: Throwable): Unit = reportFailure0(cause)

  override def monotonicNanos(): Long = {
    val back = System.nanoTime()

    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerThread[_]]) {
      thread.asInstanceOf[WorkerThread[_]].now = back
    }

    back
  }

  override def nowMillis(): Long = System.currentTimeMillis()

  override def nowMicros(): Long = {
    val now = Instant.now()
    now.getEpochSecond() * 1000000 + now.getLong(ChronoField.MICRO_OF_SECOND)
  }

  /**
   * Tries to call the current worker's `sleep`, but falls back to `sleepExternal` if needed.
   */
  def sleepInternal(
      delay: FiniteDuration,
      callback: Right[Nothing, Unit] => Unit): Function0[Unit] with Runnable = {
    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerThread[_]]) {
      val worker = thread.asInstanceOf[WorkerThread[P]]
      if (worker.isOwnedBy(this)) {
        worker.sleep(delay, callback)
      } else {
        // called from another WSTP
        sleepExternal(delay, callback)
      }
    } else {
      // not called from a WSTP
      sleepExternal(delay, callback)
    }
  }

  /**
   * Reschedule onto a worker thread and then submit the sleep.
   */
  private[this] final def sleepExternal(
      delay: FiniteDuration,
      callback: Right[Nothing, Unit] => Unit): Function0[Unit] with Runnable = {
    val scheduledAt = monotonicNanos()
    val cancel = new ExternalSleepCancel

    scheduleExternal { () =>
      val worker = Thread.currentThread().asInstanceOf[WorkerThread[_]]
      cancel.setCallback(worker.sleepLate(scheduledAt, delay, callback))
    }

    cancel
  }

  override def sleep(delay: FiniteDuration, task: Runnable): Runnable = {
    val cb = new AtomicBoolean with (Right[Nothing, Unit] => Unit) { // run at most once
      def apply(ru: Right[Nothing, Unit]) = if (compareAndSet(false, true)) {
        try {
          task.run()
        } catch {
          case ex if NonFatal(ex) =>
            reportFailure(ex)
        }
      }
    }

    val cancel = sleepInternal(delay, cb)

    () => if (cb.compareAndSet(false, true)) cancel.run() else ()
  }

  /**
   * Shut down the thread pool and clean up the pool state. Calling this method after the pool
   * has been shut down has no effect.
   */
  def shutdown(): Unit = {
    // Clear the interrupt flag.
    val interruptCalling = Thread.interrupted()
    val currentThread = Thread.currentThread()

    // Execute the shutdown logic only once.
    if (done.compareAndSet(false, true)) {
      // Note: while loops and mutable variables are used throughout this method
      // to avoid allocations of objects, since this method is expected to be
      // executed mostly in situations where the thread pool is shutting down in
      // the face of unhandled exceptions or as part of the whole JVM exiting.

      workerThreadPublisher.get()

      // Send an interrupt signal to each of the worker threads.
      var i = 0
      while (i < threadCount) {
        val workerThread = workerThreads(i)
        if (workerThread ne currentThread) {
          workerThread.interrupt()
        }
        i += 1
      }

      i = 0
      var joinTimeout = shutdownTimeout match {
        case Duration.Inf => Long.MaxValue
        case d => d.toNanos
      }
      while (i < threadCount && joinTimeout > 0) {
        val workerThread = workerThreads(i)
        if (workerThread ne currentThread) {
          val now = System.nanoTime()
          workerThread.join(joinTimeout / 1000000, (joinTimeout % 1000000).toInt)
          val elapsed = System.nanoTime() - now
          joinTimeout -= elapsed
        }
        i += 1
      }

      i = 0
      var allClosed = true
      while (i < threadCount) {
        val workerThread = workerThreads(i)
        // only close the poller if it is safe to do so, leak otherwise ...
        if ((workerThread eq currentThread) || !workerThread.isAlive()) {
          system.closePoller(pollers(i))
        } else {
          allClosed = false
        }
        i += 1
      }

      if (allClosed) {
        system.close()
      }

      var t: WorkerThread[P] = null
      while ({
        t = cachedThreads.pollFirst()
        t ne null
      }) {
        t.interrupt()
        // don't bother joining, cached threads are not doing anything interesting
      }

      // Drain the external queue.
      externalQueue.clear()
      if (interruptCalling) currentThread.interrupt()
    }
  }

  /*
   * What follows is a collection of methos used in the implementation of the
   * `cats.effect.unsafe.metrics.ComputePoolSamplerMBean` interface.
   */

  /**
   * Returns the number of [[WorkerThread]] instances backing the [[WorkStealingThreadPool]].
   *
   * @note
   *   This is a fixed value, as the [[WorkStealingThreadPool]] has a fixed number of worker
   *   threads.
   *
   * @return
   *   the number of worker threads backing the compute pool
   */
  private[unsafe] def getWorkerThreadCount(): Int =
    threadCount

  /**
   * Returns the number of active [[WorkerThread]] instances currently executing fibers on the
   * compute thread pool.
   *
   * @return
   *   the number of active worker threads
   */
  private[unsafe] def getActiveThreadCount(): Int = {
    val st = state.get()
    (st & UnparkMask) >>> UnparkShift
  }

  /**
   * Returns the number of [[WorkerThread]] instances currently searching for fibers to steal
   * from other worker threads.
   *
   * @return
   *   the number of worker threads searching for work
   */
  private[unsafe] def getSearchingThreadCount(): Int = {
    val st = state.get()
    st & SearchMask
  }

  /**
   * Returns the number of [[WorkerThread]] instances which are currently blocked due to running
   * blocking actions on the compute thread pool.
   *
   * @return
   *   the number of blocked worker threads
   */
  private[unsafe] def getBlockedWorkerThreadCount(): Int =
    blockedWorkerThreadCounter.get()

  /**
   * Returns the total number of fibers enqueued on all local queues.
   *
   * @return
   *   the total number of fibers enqueued on all local queues
   */
  private[unsafe] def getLocalQueueFiberCount(): Long =
    localQueues.map(_.size().toLong).sum

  /**
   * Returns the number of fibers which are currently asynchronously suspended.
   *
   * @note
   *   This counter is not synchronized due to performance reasons and might be reporting
   *   out-of-date numbers.
   *
   * @return
   *   the number of asynchronously suspended fibers
   */
  private[unsafe] def getSuspendedFiberCount(): Long =
    workerThreads.map(_.getSuspendedFiberCount().toLong).sum
}

private object WorkStealingThreadPool {

  /**
   * A wrapper for a cancelation callback that is created asynchronously.
   */
  private final class ExternalSleepCancel
      extends AtomicReference[Function0[Unit]]
      with Function0[Unit]
      with Runnable { callback =>
    def setCallback(cb: Function0[Unit]) = {
      val back = callback.getAndSet(cb)
      if (back eq CanceledSleepSentinel)
        cb() // we were already canceled, invoke right away
    }

    def apply() = {
      val back = callback.getAndSet(CanceledSleepSentinel)
      if (back ne null) back()
    }

    def run() = apply()
  }

  private val CanceledSleepSentinel: Function0[Unit] = () => ()
}
