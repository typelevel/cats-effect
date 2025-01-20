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

package cats.effect
package unsafe

import cats.effect.tracing.Tracing.captureTrace
import cats.effect.tracing.TracingConstants

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.{BlockContext, CanAwait}
import scala.concurrent.duration.{Duration, FiniteDuration}

import java.lang.Long.MIN_VALUE
import java.util.concurrent.{ArrayBlockingQueue, ThreadLocalRandom}
import java.util.concurrent.atomic.AtomicBoolean

import WorkerThread.{Metrics, TransferState}

/**
 * Implementation of the worker thread at the heart of the [[WorkStealingThreadPool]].
 *
 * Each worker thread is assigned exclusive write access to a single [[LocalQueue]] instance of
 * [[java.lang.Runnable]] references which other worker threads can steal when they run out of
 * work in their local queue.
 *
 * The existence of multiple work queues dramatically reduces contention in a highly parallel
 * system when compared to a fixed size thread pool whose worker threads all draw tasks from a
 * single global work queue.
 */
private[effect] final class WorkerThread[P <: AnyRef](
    idx: Int,
    private[this] var tick: Int,
    // Local queue instance with exclusive write access.
    private[this] var queue: LocalQueue,
    // The state of the `WorkerThread` (parked/unparked).
    private[unsafe] var parked: AtomicBoolean,
    // External queue used by the local queue for offloading excess fibers, as well as
    // for drawing fibers when the local queue is exhausted.
    private[this] val external: ScalQueue[AnyRef],
    // A worker-thread-local weak bag for tracking suspended fibers.
    private[this] var fiberBag: WeakBag[Runnable],
    private[this] var sleepers: TimerHeap,
    private[this] val system: PollingSystem.WithPoller[P],
    private[this] var _poller: P,
    private[this] var metrics: Metrics,
    private[this] var transferState: TransferState,
    // Reference to the `WorkStealingThreadPool` in which this thread operates.
    pool: WorkStealingThreadPool[P])
    extends Thread
    with BlockContext {

  setUncaughtExceptionHandler(pool.uncaughtExceptionHandler)

  import TracingConstants._
  import WorkStealingThreadPoolConstants._

  // Index assigned by the `WorkStealingThreadPool` for identification purposes.
  private[this] var _index: Int = idx

  /**
   * Uncontented source of randomness. By default, `java.util.Random` is thread safe, which is a
   * feature we do not need in this class, as the source of randomness is completely isolated to
   * each instance of `WorkerThread`. The instance is obtained only once at the beginning of
   * this method, to avoid the cost of the `ThreadLocal` mechanism at runtime.
   */
  private[this] var random: ThreadLocalRandom = _

  /**
   * A mutable reference to a fiber which is used to bypass the local queue when a `cede`
   * operation would enqueue a fiber to the empty local queue and then proceed to dequeue the
   * same fiber again from the queue. This not only avoids unnecessary synchronization, but also
   * avoids notifying other worker threads that new work has become available, even though
   * that's not true in tis case.
   */
  private[this] var cedeBypass: Runnable = _

  /**
   * A flag which is set whenever a blocking code region is entered. This is useful for
   * detecting nested blocking regions, in order to avoid unnecessarily spawning extra
   * [[WorkerThread]] s.
   */
  private[this] var blocking: Boolean = false

  /**
   * Contains the current monotonic time. Updated by the main worker loop as well as the
   * `Scheduler` functions when accessing system time in userspace.
   */
  private[unsafe] var now: Long = System.nanoTime()

  /**
   * Holds a reference to the fiber currently being executed by this worker thread. This field
   * is sometimes published by the `head` and `tail` of the [[LocalQueue]] and sometimes by the
   * `parked` signal of this worker thread. Threads that want to observe this value should read
   * both synchronization variables.
   */
  private[this] var _active: Runnable = _

  private val stateTransfer: ArrayBlockingQueue[TransferState] = new ArrayBlockingQueue(1)
  private[this] val runtimeBlockingExpiration: Duration = pool.runtimeBlockingExpiration

  private[effect] var currentIOFiber: IOFiber[?] = _

  private[this] val RightUnit = Right(())
  private[this] val noop = new Function0[Unit] with Runnable {
    def apply() = ()
    def run() = ()
  }

  // Identifies blocked worker, used for ranking cached threads
  val nameIndex: Int = pool.blockedWorkerThreadNamingIndex.getAndIncrement()

  // Constructor code.
  {
    // Worker threads are daemon threads.
    setDaemon(true)

    val prefix = pool.threadPrefix
    // Set the name of this thread.
    setName(s"$prefix-$idx")
  }

  private[unsafe] def poller(): P = _poller

  /**
   * Schedules the fiber for execution at the back of the local queue and notifies the work
   * stealing pool of newly available work.
   *
   * @param fiber
   *   the fiber to be scheduled on the local queue
   */
  def schedule(fiber: Runnable): Unit = {
    val rnd = random
    queue.enqueue(fiber, external, rnd)
    pool.notifyParked(rnd)
    ()
  }

  /**
   * Specifically supports the `cede` and `autoCede` mechanisms of the [[java.lang.Runnable]]
   * runloop. In the case where the local queue is empty prior to enqueuing the argument fiber,
   * the local queue is bypassed, which saves a lot of unnecessary atomic load/store operations
   * as well as a costly wake up of another thread for which there is no actual work. On the
   * other hand, if the local queue is not empty, this method enqueues the argument fiber on the
   * local queue, wakes up another thread to potentially help out with the available fibers and
   * continues with the worker thread run loop.
   *
   * @param fiber
   *   the fiber that `cede`s/`autoCede`s
   */
  def reschedule(fiber: Runnable): Unit = {
    if ((cedeBypass eq null) && queue.isEmpty()) {
      cedeBypass = fiber
    } else {
      schedule(fiber)
    }
  }

  private[this] def nanoTime(): Long = {
    // take the opportunity to update the current time, just in case other timers can benefit
    val _now = System.nanoTime()
    now = _now
    _now
  }

  def sleep(
      delay: FiniteDuration,
      callback: Right[Nothing, Unit] => Unit): Function0[Unit] with Runnable =
    sleepImpl(nanoTime(), delay.toNanos, callback)

  /**
   * A sleep that is being scheduled "late"
   */
  def sleepLate(
      scheduledAt: Long,
      delay: FiniteDuration,
      callback: Right[Nothing, Unit] => Unit): Function0[Unit] with Runnable = {
    val _now = nanoTime()
    val newDelay = delay.toNanos - (_now - scheduledAt)
    if (newDelay > 0) {
      sleepImpl(_now, newDelay, callback)
    } else {
      callback(RightUnit)
      noop
    }
  }

  private[this] def sleepImpl(
      now: Long,
      delay: Long,
      callback: Right[Nothing, Unit] => Unit): Function0[Unit] with Runnable = {
    val out = new Array[Right[Nothing, Unit] => Unit](1)

    // note that blockers aren't owned by the pool, meaning we only end up here if !blocking
    val cancel = sleepers.insert(
      now = now,
      delay = delay,
      callback = callback,
      out = out
    )

    val cb = out(0)
    if (cb ne null) cb(RightUnit)

    cancel
  }

  /**
   * Checks whether this [[WorkerThread]] operates within the [[WorkStealingThreadPool]]
   * provided as an argument to this method. The implementation checks whether the provided
   * [[WorkStealingThreadPool]] matches the reference of the pool provided when this
   * [[WorkerThread]] was constructed.
   *
   * @note
   *   When blocking code is being executed on this worker thread, it is important to delegate
   *   all scheduling operation to the external queue because at that point, the blocked worker
   *   thread has already been replaced by a new worker thread instance which owns the local
   *   queue and so, the current worker thread must be treated as an external thread.
   *
   * @param threadPool
   *   a work stealing thread pool reference
   * @return
   *   `true` if this worker thread is owned by the provided work stealing thread pool, `false`
   *   otherwise
   */
  def isOwnedBy(threadPool: WorkStealingThreadPool[?]): Boolean =
    (pool eq threadPool) && !blocking

  /**
   * Checks whether this [[WorkerThread]] operates within the [[WorkStealingThreadPool]]
   * provided as an argument to this method. The implementation checks whether the provided
   * [[WorkStealingThreadPool]] matches the reference of the pool provided when this
   * [[WorkerThread]] was constructed.
   *
   * @param threadPool
   *   a work stealing thread pool reference
   * @return
   *   `true` if this worker thread is owned by the provided work stealing thread pool, `false`
   *   otherwise
   */
  def canExecuteBlockingCodeOn(threadPool: WorkStealingThreadPool[?]): Boolean =
    pool eq threadPool

  /**
   * Registers a suspended fiber.
   *
   * @param fiber
   *   the suspended fiber to be registered
   * @return
   *   a handle for deregistering the fiber on resumption
   */
  def monitor(fiber: Runnable): WeakBag.Handle =
    fiberBag.insert(fiber)

  /**
   * The index of the worker thread.
   */
  private[unsafe] def index: Int =
    _index

  /**
   * A reference to the active fiber.
   */
  private[unsafe] def active: Runnable =
    _active

  /**
   * Sets the active fiber reference.
   *
   * @param fiber
   *   the new active fiber
   */
  private[unsafe] def active_=(fiber: Runnable): Unit = {
    _active = fiber
  }

  /**
   * Returns a snapshot of suspended fibers tracked by this worker thread.
   *
   * @return
   *   a set of suspended fibers tracked by this worker thread
   */
  private[unsafe] def suspendedTraces(): Map[Runnable, Trace] = {
    val foreign = mutable.Map.empty[Runnable, Trace]
    fiberBag.forEach(r => foreign ++= captureTrace(r))
    foreign.toMap
  }

  private[unsafe] def ownsPoller(poller: P): Boolean =
    poller eq _poller

  private[unsafe] def ownsTimers(timers: TimerHeap): Boolean =
    sleepers eq timers

  /**
   * The run loop of the [[WorkerThread]].
   */
  override def run(): Unit = {
    val self = this
    random = ThreadLocalRandom.current()
    val rnd = random

    val done = pool.done

    /*
     * This method is called when the local queue is empty, and will return only when work is
     * found.
     *
     * STEP 1: Fall back to checking the external queue after a failed dequeue from the local
     * queue. Depending on the outcome of this check, the `WorkerThread` transitions to
     * executing fibers from the local queue in the case of a successful dequeue from the
     * external queue. Otherwise, the `WorkerThread` continues with asking for permission to
     * steal from other `WorkerThread`s.
     *
     * Depending on the outcome of this request, the `WorkerThread` starts looking for fibers to
     * steal from the local queues of other worker threads (if permission was granted), or parks
     * directly. In this case, there is less bookkeeping to be done compared to the case where a
     * worker was searching for work prior to parking.
     *
     * STEP 2 (conditional): The `WorkerThread` has been allowed to steal fibers from other
     * worker threads. If the attempt is successful, the first fiber is executed directly and
     * the `WorkerThread` transitions to executing fibers from the local queue. If the attempt
     * is unsuccessful, the worker thread announces to the pool that it was unable to find any
     * work and parks.
     *
     * STEP 3 (while loop): After the worker thread has been unparked, it transitions to looking
     * for work in the external queue while also holding a permission to steal fibers from other
     * worker threads.
     *
     * STEP 3.1: The `WorkerThread` has been unparked an is looking for work in the external
     * queue. If it manages to find work there, it announces to the work stealing thread pool
     * that it is no longer searching for work and continues to execute fibers from the local
     * queue. Otherwise, it transitions to searching for work to steal from the local queues of
     * other worker threads because the permission to steal is implicitly held by threads that
     * have been unparked.
     *
     * STEP 3.2: The `WorkerThread` has been allowed to steal fibers from other worker threads.
     * If the attempt is successful, the first fiber is executed directly and the `WorkerThread`
     * transitions to executing fibers from the local queue. If the attempt is unsuccessful, the
     * worker thread announces to the pool that it was unable to find any work and parks.
     *
     * A note on the implementation. Some of the steps seem like they have overlapping logic.
     * This is indeed true, but it is a conscious decision. The logic is carefully unrolled and
     * compiled into a shallow while loop.
     */
    def lookForWork(): Unit = {
      val permissionToSteal = {
        // Check the external queue after a failed dequeue from the local
        // queue (due to the local queue being empty).
        val element = external.poll(rnd)
        if (element.isInstanceOf[Array[Runnable]]) {
          val batch = element.asInstanceOf[Array[Runnable]]
          // The dequeued element was a batch of fibers. Enqueue the whole
          // batch on the local queue and execute the first fiber.
          // It is safe to directly enqueue the whole batch because we know
          // that in this state of the worker thread state machine, the
          // local queue is empty.
          val fiber = queue.enqueueBatch(batch, self)
          // Many fibers have been exchanged between the external and the
          // local queue. Notify other worker threads.
          pool.notifyParked(rnd)
          try fiber.run()
          catch {
            case t if UnsafeNonFatal(t) => pool.reportFailure(t)
            case t: Throwable => IOFiber.onFatalFailure(t)
          }

          // Transition to executing fibers from the local queue.
          return
        } else if (element.isInstanceOf[Runnable]) {
          val fiber = element.asInstanceOf[Runnable]

          if (isStackTracing) {
            _active = fiber
            parked.lazySet(false)
          }

          // The dequeued element is a single fiber. Execute it immediately.
          try fiber.run()
          catch {
            case t if UnsafeNonFatal(t) => pool.reportFailure(t)
            case t: Throwable => IOFiber.onFatalFailure(t)
          }

          // Transition to executing fibers from the local queue.
          return
        } else {
          // Could not find any fibers in the external queue. Proceed to ask
          // for permission to steal fibers from other `WorkerThread`s.
          if (pool.transitionWorkerToSearching()) {
            // Permission granted, proceed to steal.
            true
          } else {
            // Permission denied, proceed to park.
            // Set the worker thread parked signal.
            if (isStackTracing) {
              _active = null
            }

            parked.lazySet(true)
            // Announce that the worker thread is parking.
            pool.transitionWorkerToParked()
            // Park the thread.
            if (park())
              return // Work found, transition to executing fibers from the local queue.
            else
              false
          }
        }
      }

      if (permissionToSteal) {
        // update the current time
        now = System.nanoTime()

        // First try to steal some expired timers.
        val stoleTimers = pool.stealTimers(now, rnd)

        // Try stealing fibers from other worker threads.
        val fiber = pool.stealFromOtherWorkerThread(index, rnd, self)

        if (stoleTimers || (fiber ne null)) {
          // Successful steal. Announce that the current thread is no longer
          // looking for work.
          pool.transitionWorkerFromSearching(rnd)

          if (fiber ne null) {
            // Run the stolen fiber.
            try fiber.run()
            catch {
              case t if UnsafeNonFatal(t) => pool.reportFailure(t)
              case t: Throwable => IOFiber.onFatalFailure(t)
            }
          }

          // Transition to executing fibers from the local queue.
          return
        } else {
          // Stealing attempt is unsuccessful. Park.
          // Set the worker thread parked signal.
          if (isStackTracing) {
            _active = null
          }

          parked.lazySet(true)
          // Announce that the worker thread which was searching for work is now
          // parking. This checks if the parking worker thread was the last
          // actively searching thread.
          if (pool.transitionWorkerToParkedWhenSearching()) {
            // If this was indeed the last actively searching thread, do another
            // global check of the pool. Other threads might be busy with their
            // local queues or new work might have arrived on the external
            // queue. Another thread might be able to help.
            pool.notifyIfWorkPending(rnd)
          }
          // Park the thread.
          if (park())
            return // Work found, transition to executing fibers from the local queue.
          else
            () // Proceed to while loop.
        }
      }

      while (!done.get()) {
        // Check the external queue after being unparked
        val element = external.poll(rnd)
        if (element.isInstanceOf[Array[Runnable]]) {
          val batch = element.asInstanceOf[Array[Runnable]]
          // Announce that the current thread is no longer looking for work.
          pool.transitionWorkerFromSearching(rnd)

          // The dequeued element was a batch of fibers. Enqueue the whole
          // batch on the local queue and execute the first fiber.
          // It is safe to directly enqueue the whole batch because we know
          // that in this state of the worker thread state machine, the
          // local queue is empty.
          val fiber = queue.enqueueBatch(batch, self)
          // Many fibers have been exchanged between the external and the
          // local queue. Notify other worker threads.
          pool.notifyParked(rnd)
          try fiber.run()
          catch {
            case t if UnsafeNonFatal(t) => pool.reportFailure(t)
            case t: Throwable => IOFiber.onFatalFailure(t)
          }

          // Transition to executing fibers from the local queue.
          return
        } else if (element.isInstanceOf[Runnable]) {
          val fiber = element.asInstanceOf[Runnable]
          // Announce that the current thread is no longer looking for work.

          if (isStackTracing) {
            _active = fiber
            parked.lazySet(false)
          }

          pool.transitionWorkerFromSearching(rnd)

          // The dequeued element is a single fiber. Execute it immediately.
          try fiber.run()
          catch {
            case t if UnsafeNonFatal(t) => pool.reportFailure(t)
            case t: Throwable => IOFiber.onFatalFailure(t)
          }

          // Transition to executing fibers from the local queue.
          return
        }

        // Transition to stealing fibers from other `WorkerThread`s.
        // The permission is held implicitly by threads right after they
        // have been woken up.

        // update the current time
        now = System.nanoTime()

        // First try to steal some expired timers.
        val stoleTimers = pool.stealTimers(now, rnd)

        // Try stealing fibers from other worker threads.
        val fiber = pool.stealFromOtherWorkerThread(index, rnd, self)

        if (stoleTimers || (fiber ne null)) {
          // Successful steal. Announce that the current thread is no longer
          // looking for work.
          pool.transitionWorkerFromSearching(rnd)

          if (fiber ne null) {
            // Run the stolen fiber.
            try fiber.run()
            catch {
              case t if UnsafeNonFatal(t) => pool.reportFailure(t)
              case t: Throwable => IOFiber.onFatalFailure(t)
            }
          }

          // Transition to executing fibers from the local queue.
          return
        } else {
          // Stealing attempt is unsuccessful. Park.
          // Set the worker thread parked signal.
          if (isStackTracing) {
            _active = null
          }

          parked.lazySet(true)
          // Announce that the worker thread which was searching for work is now
          // parking. This checks if the parking worker thread was the last
          // actively searching thread.
          if (pool.transitionWorkerToParkedWhenSearching()) {
            // If this was indeed the last actively searching thread, do another
            // global check of the pool. Other threads might be busy with their
            // local queues or new work might have arrived on the external
            // queue. Another thread might be able to help.
            pool.notifyIfWorkPending(rnd)
          }
          // Park the thread.
          if (park())
            return // Work found, transition to executing fibers from the local queue.
          else
            () // loop
        }
      }
    }

    // returns whether work was found
    def park(): Boolean = {
      metrics.incrementParkedCount()

      val tt = sleepers.peekFirstTriggerTime()
      val workFound = if (tt == MIN_VALUE) { // no sleepers
        if (parkLoop()) {
          // we polled something, so go straight to local queue stuff
          pool.transitionWorkerFromSearching(rnd)
          true
        } else {
          // we were interrupted, look for more work in the external queue
          false
        }
      } else {
        if (parkUntilNextSleeper()) {
          // we made it to the end of our sleeping/polling, so go straight to local queue stuff
          pool.transitionWorkerFromSearching(rnd)
          true
        } else {
          // we were interrupted, look for more work in the external queue
          false
        }
      }

      if (!workFound) {
        // after being unparked, we re-check sleepers;
        val nextTrigger = sleepers.peekFirstTriggerTime()
        if ((nextTrigger != MIN_VALUE) && (nextTrigger - now <= 0L)) {
          pool.transitionWorkerFromSearching(rnd)
          true
        } else {
          false
        }
      } else {
        true
      }
    }

    @tailrec
    def drainReadyEvents(result: PollResult, acc: Boolean): Boolean =
      if (result ne PollResult.Interrupted) {
        val tasksScheduled = system.processReadyEvents(_poller) | acc
        if (result eq PollResult.Complete) tasksScheduled
        else drainReadyEvents(system.poll(_poller, 0), tasksScheduled)
      } else {
        acc
      }

    // returns true if polled event, false if unparked
    def parkLoop(): Boolean = {
      while (!done.get()) {
        // Park the thread until further notice.
        val start = System.nanoTime()
        metrics.incrementPolledCount()
        val pollResult = system.poll(_poller, -1)
        now = System.nanoTime() // update now
        metrics.addIdleTime(now - start)

        // the only way we can be interrupted here is if it happened *externally* (probably sbt)
        if (isInterrupted()) {
          pool.shutdown()
        } else if (pollResult ne PollResult.Interrupted) {
          if (parked.getAndSet(false))
            pool.doneSleeping()
          // TODO, if no tasks scheduled could fastpath back to park?
          val _ = drainReadyEvents(pollResult, false)
          return true
        } else if (!parked.get()) { // Spurious wakeup check.
          return false
        } else // loop
          ()
      }
      false
    }

    // returns true if timed out or polled event, false if unparked
    @tailrec
    def parkUntilNextSleeper(): Boolean = {
      if (done.get()) {
        false
      } else {
        val triggerTime = sleepers.peekFirstTriggerTime()
        if (triggerTime == MIN_VALUE) {
          // no sleeper (it was removed)
          parkLoop()
        } else {
          now = System.nanoTime()
          val nanos = triggerTime - now

          if (nanos > 0L) {
            val start = now
            metrics.incrementPolledCount()
            val pollResult = system.poll(_poller, nanos)
            // we already parked and time passed, so update time again
            // it doesn't matter if we timed out or were awakened, the update is free-ish
            now = System.nanoTime()
            metrics.addIdleTime(now - start)

            if (isInterrupted()) {
              pool.shutdown()
              false // we know `done` is `true`
            } else {
              // no matter why we woke up, there may be timers or events ready
              val polled = pollResult ne PollResult.Interrupted
              if (polled || (triggerTime - now <= 0)) {
                // we timed out or polled an event
                if (parked.getAndSet(false)) {
                  pool.doneSleeping()
                }
                if (polled) { // TODO, if no tasks scheduled and no timers could fastpath back to park?
                  val _ = drainReadyEvents(pollResult, false)
                }
                true
              } else { // we were either awakened spuriously or intentionally
                if (parked.get()) // awakened spuriously, re-check next sleeper
                  parkUntilNextSleeper()
                else // awakened intentionally, but not due to a timer or event
                  false
              }
            }
          } else {
            // a timer already expired
            if (parked.getAndSet(false)) {
              pool.doneSleeping()
            }
            true
          }
        }
      }
    }

    while (!done.get()) {
      if (blocking) {
        // The worker thread was blocked before. It is no longer part of the
        // core pool and needs to be cached.

        // First of all, remove the references to data structures of the core
        // pool because they have already been transferred to another thread
        // which took the place of this one.
        queue = null
        sleepers = null
        parked = null
        fiberBag = null
        _active = null
        _poller = null.asInstanceOf[P]
        metrics = null
        transferState = null

        try {
          // Try to transfer this thread via the cached threads data structure, to be picked up
          // by another thread in the future.
          val len = runtimeBlockingExpiration.length
          val unit = runtimeBlockingExpiration.unit
          if (pool.cachedThreads.tryTransfer(this, len, unit)) {
            // Someone accepted the transfer of this thread and will transfer the state soon.
            val newState = stateTransfer.take()
            init(newState)
          } else {
            // The timeout elapsed and no one woke up this thread. It's time to exit.
            pool.blockedWorkerThreadCounter.decrementAndGet()
            return
          }
        } catch {
          case _: InterruptedException =>
            // This thread was interrupted while cached. This should only happen
            // during the shutdown of the pool. Nothing else to be done, just
            // exit.
            return
        }
      }

      if ((tick & ExternalWorkTicksMask) == 0) {
        if ((tick & PollingTicksMask) == 0) {
          if (pool.blockedThreadDetectionEnabled) {
            // TODO prefetch pool.workerThread or Thread.State.BLOCKED ?
            // TODO check that branch elimination makes it free when off
            val idx = index
            val threadCount = pool.getWorkerThreadCount()
            val otherIdx = (idx + random.nextInt(threadCount - 1) + 1) % threadCount
            val thread = pool.getWorkerThread(otherIdx)
            val state = thread.getState()
            val parked = thread.parked

            // we have to check for null since there's a race here when threads convert to blockers
            // by reading parked *after* reading state, we avoid misidentifying blockers as blocked
            if (parked != null && !parked
                .get() && (state == Thread.State.BLOCKED || state == Thread
                .State
                .WAITING || state == Thread.State.TIMED_WAITING)) {
              System.err.println(mkWarning(state, thread.getStackTrace()))
            }
          }

          // Clean up any externally canceled timers
          sleepers.packIfNeeded()
          // give the polling system a chance to discover events
          metrics.incrementPolledCount()
          if (system.needsPoll(_poller)) {
            val _ = drainReadyEvents(system.poll(_poller, 0), false)
          }

          // update the current time
          now = System.nanoTime()
        } else {
          // Obtain a fiber or batch of fibers from the external queue.
          val element = external.poll(rnd)
          if (element.isInstanceOf[Array[Runnable]]) {
            val batch = element.asInstanceOf[Array[Runnable]]
            // The dequeued element was a batch of fibers. Enqueue the whole
            // batch on the local queue and execute the first fiber.

            // Make room for the batch if the local queue cannot accommodate
            // all of the fibers as is.
            queue.drainBatch(external, rnd)

            val fiber = queue.enqueueBatch(batch, self)
            // Many fibers have been exchanged between the external and the
            // local queue. Notify other worker threads.
            pool.notifyParked(rnd)

            try fiber.run()
            catch {
              case t if UnsafeNonFatal(t) => pool.reportFailure(t)
              case t: Throwable => IOFiber.onFatalFailure(t)
            }
          } else if (element.isInstanceOf[Runnable]) {
            val fiber = element.asInstanceOf[Runnable]

            if (isStackTracing) {
              _active = fiber
              parked.lazySet(false)
            }

            // The dequeued element is a single fiber. Execute it immediately.
            try fiber.run()
            catch {
              case t if UnsafeNonFatal(t) => pool.reportFailure(t)
              case t: Throwable => IOFiber.onFatalFailure(t)
            }
          }
        }

        // Transition to executing fibers from the local queue.
      } else {
        // Call all of our expired timers:
        var cont = true
        while (cont) {
          val cb = sleepers.pollFirstIfTriggered(now)
          if (cb ne null) {
            cb(RightUnit)
          } else {
            cont = false
          }
        }

        // Check the queue bypass reference before dequeueing from the local
        // queue.
        val fiber = if (cedeBypass eq null) {
          // The queue bypass reference is empty.
          // Fall back to the local queue.
          queue.dequeue(self)
        } else {
          // Fetch and null out the queue bypass reference.
          val f = cedeBypass
          cedeBypass = null
          f
        }
        if (fiber ne null) {
          // Run the fiber.
          try fiber.run()
          catch {
            case t if UnsafeNonFatal(t) => pool.reportFailure(t)
            case t: Throwable => IOFiber.onFatalFailure(t)
          }
        } else {
          // Transition to checking the external queue.
          lookForWork()
        }

        // Continue executing fibers from the local queue.
      }

      tick += 1
    }
  }

  private[this] def mkWarning(state: Thread.State, stackTrace: Array[StackTraceElement]) = {
    def formatTrace(st: Array[StackTraceElement]): String = {
      val sb = new StringBuilder()
      var i = 0
      while (i < st.length) {
        sb.append("  at ")
        sb.append(st(i).toString())
        if (i != st.length - 1)
          sb.append("\n")
        i += 1
      }
      sb.toString()
    }
    s"""|[WARNING] A Cats Effect worker thread was detected to be in a blocked state ($state)
        |${formatTrace(stackTrace)}
        |This is very likely to be due to suspending a blocking call in IO via
        |`IO.delay` or `IO.apply`. If this is the case then you should use
        |`IO.blocking` or `IO.interruptible` instead.""".stripMargin
  }

  /**
   * Support code that must be run before executing a blocking action on this thread.
   *
   * The current thread creates a replacement worker thread (or reuses a cached one) that will
   * take its place in the pool and does a complete transfer of ownership of the data structures
   * referenced by the thread. It then replaces the reference that the pool has of this worker
   * thread with the reference of the new worker thread. At this point, the replacement worker
   * thread is started, and the current thread is no longer recognized as a worker thread of the
   * work stealing thread pool. This is done by setting the `blocking` flag, which signifies
   * that the blocking region of code has been entered. This flag is respected when scheduling
   * fibers (it can happen that the blocking region or the fiber run loop right after it wants
   * to execute a scheduling call) and since this thread is now treated as an external thread,
   * all fibers are scheduled on the external queue. The `blocking` flag is also respected by
   * the `run()` method of this thread such that the next time that the main loop needs to
   * continue, it will be cached for a period of time instead. Finally, the `blocking` flag is
   * useful when entering nested blocking regions. In this case, there is no need to spawn a
   * replacement worker thread.
   */
  def prepareForBlocking(): Unit = {
    if (blocking) {
      // This `WorkerThread` has already been prepared for blocking.
      // There is no need to spawn another `WorkerThread`.
    } else {
      metrics.incrementBlockingCount()

      // Spawn a new `WorkerThread` to take the place of this thread, as the
      // current thread prepares to execute a blocking action.

      // We'll transfer our local queue to the new/cached thread;
      // don't forget to also transfer our cede bypass (if any):
      val bypass = cedeBypass
      if (bypass ne null) {
        queue.enqueue(bypass, external, random)
        cedeBypass = null
      }

      // Logically become a blocking thread.
      blocking = true

      val prefix = pool.blockerThreadPrefix
      // Set the name of this thread to a blocker prefixed name.
      setName(s"$prefix-$nameIndex")

      val cached = pool.cachedThreads.poll()
      if (cached ne null) {
        // There is a cached worker thread that can be reused.
        val idx = index
        pool.replaceWorker(idx, cached)
        // Transfer the data structures to the cached thread and wake it up.
        transferState.index = idx
        transferState.tick = tick + 1
        val _ = cached.stateTransfer.offer(transferState)
      } else {
        // Spawn a new `WorkerThread`, a literal clone of this one. It is safe to
        // transfer ownership of the local queue and the parked signal to the new
        // thread because the current one will only execute the blocking action
        // and die. Any other worker threads trying to steal from the local queue
        // being transferred need not know of the fact that the underlying worker
        // thread is being changed. Transferring the parked signal is safe because
        // a worker thread about to run blocking code is **not** parked, and
        // therefore, another worker thread would not even see it as a candidate
        // for unparking.
        metrics.incrementRespawnCount()
        val idx = index
        val clone =
          new WorkerThread(
            idx,
            tick + 1,
            queue,
            parked,
            external,
            fiberBag,
            sleepers,
            system,
            _poller,
            metrics,
            transferState,
            pool)
        // Make sure the clone gets our old name:
        val clonePrefix = pool.threadPrefix
        clone.setName(s"$clonePrefix-$idx")
        pool.replaceWorker(idx, clone)
        pool.blockedWorkerThreadCounter.incrementAndGet()
        clone.start()
      }
    }
  }

  /**
   * A mechanism for executing support code before executing a blocking action.
   *
   * @note
   *   There is no reason to enclose any code in a `try/catch` block because the only way this
   *   code path can be exercised is through `IO.delay`, which already handles exceptions.
   */
  override def blockOn[T](thunk: => T)(implicit permission: CanAwait): T = {
    prepareForBlocking()
    thunk
  }

  private[this] def init(newState: TransferState): Unit = {
    val newIdx = newState.index
    _index = newIdx
    tick = newState.tick
    queue = pool.localQueues(newIdx)
    sleepers = pool.sleepers(newIdx)
    parked = pool.parkedSignals(newIdx)
    fiberBag = pool.fiberBags(newIdx)
    _poller = pool.pollers(newIdx)
    metrics = pool.metrices(newIdx)
    transferState = newState

    // Reset the name of the thread to the regular prefix.
    val prefix = pool.threadPrefix
    setName(s"$prefix-${_index}")

    blocking = false
  }

  /**
   * Returns the number of fibers which are currently asynchronously suspended and tracked by
   * this worker thread.
   *
   * @note
   *   This counter is not synchronized due to performance reasons and might be reporting
   *   out-of-date numbers.
   *
   * @return
   *   the number of asynchronously suspended fibers
   */
  def getSuspendedFiberCount(): Int =
    fiberBag.size
}

private[effect] object WorkerThread {

  private[unsafe] final class TransferState {
    var index: Int = _
    var tick: Int = _
  }

  final class Metrics {
    private[this] var idleTime: Long = 0
    def getIdleTime(): Long = idleTime
    def addIdleTime(x: Long): Unit = idleTime += x

    private[this] var parkedCount: Long = 0
    def getParkedCount(): Long = parkedCount
    def incrementParkedCount(): Unit = parkedCount += 1

    private[this] var polledCount: Long = 0
    def getPolledCount(): Long = polledCount
    def incrementPolledCount(): Unit = polledCount += 1

    private[this] var blockingCount: Long = 0
    def getBlockingCount(): Long = blockingCount
    def incrementBlockingCount(): Unit = blockingCount += 1

    private[this] var respawnCount: Long = 0
    def getRespawnCount(): Long = respawnCount
    def incrementRespawnCount(): Unit = respawnCount += 1
  }

}
