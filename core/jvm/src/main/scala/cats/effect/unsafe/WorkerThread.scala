/*
 * Copyright 2020-2023 Typelevel
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

import scala.annotation.{switch, tailrec}
import scala.collection.mutable
import scala.concurrent.{BlockContext, CanAwait}
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.control.NonFatal

import java.lang.Long.MIN_VALUE
import java.util.concurrent.{LinkedTransferQueue, ThreadLocalRandom}
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport

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
private final class WorkerThread(
    idx: Int,
    // Local queue instance with exclusive write access.
    private[this] var queue: LocalQueue,
    // The state of the `WorkerThread` (parked/unparked).
    private[unsafe] var parked: AtomicBoolean,
    // External queue used by the local queue for offloading excess fibers, as well as
    // for drawing fibers when the local queue is exhausted.
    private[this] val external: ScalQueue[AnyRef],
    // A worker-thread-local weak bag for tracking suspended fibers.
    private[this] var fiberBag: WeakBag[Runnable],
    private[this] var sleepers: TimerSkipList,
    // Reference to the `WorkStealingThreadPool` in which this thread operates.
    private[this] val pool: WorkStealingThreadPool)
    extends Thread
    with BlockContext {

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
   * Holds a reference to the fiber currently being executed by this worker thread. This field
   * is sometimes published by the `head` and `tail` of the [[LocalQueue]] and sometimes by the
   * `parked` signal of this worker thread. Threads that want to observe this value should read
   * both synchronization variables.
   */
  private[this] var _active: Runnable = _

  private val indexTransfer: LinkedTransferQueue[Integer] = new LinkedTransferQueue()
  private[this] val runtimeBlockingExpiration: Duration = pool.runtimeBlockingExpiration

  val nameIndex: Int = pool.blockedWorkerThreadNamingIndex.incrementAndGet()

  // Constructor code.
  {
    // Worker threads are daemon threads.
    setDaemon(true)

    val prefix = pool.threadPrefix
    // Set the name of this thread.
    setName(s"$prefix-$nameIndex")
  }

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

  def sleep(delay: FiniteDuration, callback: Right[Nothing, Unit] => Unit): Runnable = {
    // note that blockers aren't owned by the pool, meaning we only end up here if !blocking
    sleepers.insert(
      now = System.nanoTime(),
      delay = delay.toNanos,
      callback = callback,
      tlr = random
    )
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
  def isOwnedBy(threadPool: WorkStealingThreadPool): Boolean =
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
  def canExecuteBlockingCodeOn(threadPool: WorkStealingThreadPool): Boolean =
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

  /**
   * The run loop of the [[WorkerThread]].
   */
  override def run(): Unit = {
    val self = this
    random = ThreadLocalRandom.current()
    val rnd = random
    val RightUnit = IOFiber.RightUnit

    /*
     * A counter (modulo `ExternalQueueTicks`) which represents the
     * `WorkerThread` finite state machine. The following values have special
     * semantics explained here:
     *
     *   0: To increase the fairness towards fibers scheduled by threads which
     *      are external to the `WorkStealingThreadPool`, every
     *      `ExternalQueueTicks` number of iterations, the external queue takes
     *      precedence over the local queue.
     *
     *      If a fiber is successfully dequeued from the external queue, it will
     *      be executed immediately. If a batch of fibers is dequeued instead,
     *      the whole batch is enqueued on the local queue and other worker
     *      threads are notified of existing work available for stealing. The
     *      `WorkerThread` unconditionally transitions to executing fibers from
     *      the local queue (state value 4 and larger).
     *
     *      This state occurs "naturally" after a certain number of executions
     *      from the local queue (when the state value wraps around modulo
     *      `ExternalQueueTicks`).
     *
     *   1: Fall back to checking the external queue after a failed dequeue from
     *      the local queue. Depending on the outcome of this check, the
     *      `WorkerThread` transitions to executing fibers from the local queue
     *      in the case of a successful dequeue from the external queue (state
     *      value 4 and larger). Otherwise, the `WorkerThread` continues with
     *      asking for permission to steal from other `WorkerThread`s.
     *
     *      Depending on the outcome of this request, the `WorkerThread` starts
     *      looking for fibers to steal from the local queues of other worker
     *      threads (if permission was granted, state value 2), or parks
     *      directly. In this case, there is less bookkeeping to be done
     *      compared to the case where a worker was searching for work prior to
     *      parking. After the worker thread has been unparked, it transitions
     *      to looking for work in the external queue (state value 3) while also
     *      holding a permission to steal fibers from other worker threads.
     *
     *   2: The `WorkerThread` has been allowed to steal fibers from other
     *      worker threads. If the attempt is successful, the first fiber is
     *      executed directly and the `WorkerThread` transitions to executing
     *      fibers from the local queue (state value 4 and larger). If the
     *      attempt is unsuccessful, the worker thread announces to the pool
     *      that it was unable to find any work and parks.
     *
     *   3: The `WorkerThread` has been unparked an is looking for work in the
     *      external queue. If it manages to find work there, it announces to
     *      the work stealing thread pool that it is no longer searching for
     *      work and continues to execute fibers from the local queue (state
     *      value 4 and larger). Otherwise, it transitions to searching for work
     *      to steal from the local queues of other worker threads because the
     *      permission to steal is implicitly held by threads that have been
     *      unparked (state value 2).
     *
     *   4 and larger: Look for fibers to execute in the local queue. In case
     *      of a successful dequeue from the local queue, increment the state
     *      value. In case of a failed dequeue from the local queue, transition
     *      to looking for fibers in the external queue (state value 1).
     *
     * A note on the implementation. Some of the states seem like they have
     * overlapping logic. This is indeed true, but it is a conscious decision.
     * The logic is carefully unrolled and compiled into a shallow `tableswitch`
     * instead of a deeply nested sequence of `if/else` statements. This change
     * has lead to a non-negligible 15-20% increase in single-threaded
     * performance.
     */
    var state = 4

    val done = pool.done

    // returns next state after parking
    def park(): Int = {
      val tt = sleepers.peekFirstTriggerTime()
      val nextState = if (tt == MIN_VALUE) { // no sleepers
        parkLoop()

        // After the worker thread has been unparked, look for work in the
        // external queue.
        3
      } else {
        if (parkUntilNextSleeper()) {
          // we made it to the end of our sleeping, so go straight to local queue stuff
          pool.transitionWorkerFromSearching(rnd)
          4
        } else {
          // we were interrupted, look for more work in the external queue
          3
        }
      }

      if (nextState != 4) {
        // after being unparked, we re-check sleepers;
        // if we find an already expired one, we go
        // immediately to state 4 (local queue stuff):
        val nextTrigger = sleepers.peekFirstTriggerTime()
        if ((nextTrigger != MIN_VALUE) && (nextTrigger - System.nanoTime() <= 0L)) {
          pool.transitionWorkerFromSearching(rnd)
          4
        } else {
          nextState
        }
      } else {
        nextState
      }
    }

    def parkLoop(): Unit = {
      var cont = true
      while (cont && !done.get()) {
        // Park the thread until further notice.
        LockSupport.park(pool)

        // the only way we can be interrupted here is if it happened *externally* (probably sbt)
        if (isInterrupted())
          pool.shutdown()
        else
          // Spurious wakeup check.
          cont = parked.get()
      }
    }

    // returns true if timed out, false if unparked
    @tailrec
    def parkUntilNextSleeper(): Boolean = {
      if (done.get()) {
        false
      } else {
        val triggerTime = sleepers.peekFirstTriggerTime()
        if (triggerTime == MIN_VALUE) {
          // no sleeper (it was removed)
          parkLoop()
          false
        } else {
          val now = System.nanoTime()
          val nanos = triggerTime - now

          if (nanos > 0L) {
            LockSupport.parkNanos(pool, nanos)

            if (isInterrupted()) {
              pool.shutdown()
              false // we know `done` is `true`
            } else {
              if (parked.get()) {
                // we were either awakened spuriously, or we timed out
                if (triggerTime - System.nanoTime() <= 0) {
                  // we timed out
                  if (parked.getAndSet(false)) {
                    pool.doneSleeping()
                  }
                  true
                } else {
                  // awakened spuriously, re-check next sleeper
                  parkUntilNextSleeper()
                }
              } else {
                // awakened intentionally
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

        // Add this thread to the cached threads data structure, to be picked up
        // by another thread in the future.
        pool.cachedThreads.add(this)
        try {
          val len = runtimeBlockingExpiration.length
          val unit = runtimeBlockingExpiration.unit
          var newIdx: Integer = indexTransfer.poll(len, unit)
          if (newIdx eq null) {
            // The timeout elapsed and no one woke up this thread. Try to remove
            // the thread from the cached threads data structure.
            if (pool.cachedThreads.remove(this)) {
              // The thread was successfully removed. It's time to exit.
              pool.blockedWorkerThreadCounter.decrementAndGet()
              return
            } else {
              // Someone else concurrently stole this thread from the cached
              // data structure and will transfer the data soon. Time to wait
              // for it again.
              newIdx = indexTransfer.take()
              init(newIdx)
            }
          } else {
            // Some other thread woke up this thread. Time to take its place.
            init(newIdx)
          }
        } catch {
          case _: InterruptedException =>
            // This thread was interrupted while cached. This should only happen
            // during the shutdown of the pool. Nothing else to be done, just
            // exit.
            return
        }

        // Reset the state of the thread for resumption.
        blocking = false
        state = 4
      }

      ((state & ExternalQueueTicksMask): @switch) match {
        case 0 =>
          if (pool.blockedThreadDetectionEnabled) {
            // TODO prefetch pool.workerThread or Thread.State.BLOCKED ?
            // TODO check that branch elimination makes it free when off
            var otherIdx = random.nextInt(pool.getWorkerThreads.length)
            if (otherIdx == idx) {
              otherIdx =
                (idx + Math.max(1, random.nextInt(pool.getWorkerThreads.length - 1))) % pool
                  .getWorkerThreads
                  .length
            }
            val thread = pool.getWorkerThreads(otherIdx)
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

          // Obtain a fiber or batch of fibers from the external queue.
          val element = external.poll(rnd)
          if (element.isInstanceOf[Array[Runnable]]) {
            val batch = element.asInstanceOf[Array[Runnable]]
            // The dequeued element was a batch of fibers. Enqueue the whole
            // batch on the local queue and execute the first fiber.

            // Make room for the batch if the local queue cannot accomodate
            // all of the fibers as is.
            queue.drainBatch(external, rnd)

            val fiber = queue.enqueueBatch(batch, self)
            // Many fibers have been exchanged between the external and the
            // local queue. Notify other worker threads.
            pool.notifyParked(rnd)

            try fiber.run()
            catch {
              case t if NonFatal(t) => pool.reportFailure(t)
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
              case t if NonFatal(t) => pool.reportFailure(t)
              case t: Throwable => IOFiber.onFatalFailure(t)
            }
          }

          // Transition to executing fibers from the local queue.
          state = 4

        case 1 =>
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
              case t if NonFatal(t) => pool.reportFailure(t)
              case t: Throwable => IOFiber.onFatalFailure(t)
            }

            // Transition to executing fibers from the local queue.
            state = 4
          } else if (element.isInstanceOf[Runnable]) {
            val fiber = element.asInstanceOf[Runnable]

            if (isStackTracing) {
              _active = fiber
              parked.lazySet(false)
            }

            // The dequeued element is a single fiber. Execute it immediately.
            try fiber.run()
            catch {
              case t if NonFatal(t) => pool.reportFailure(t)
              case t: Throwable => IOFiber.onFatalFailure(t)
            }

            // Transition to executing fibers from the local queue.
            state = 4
          } else {
            // Could not find any fibers in the external queue. Proceed to ask
            // for permission to steal fibers from other `WorkerThread`s.
            if (pool.transitionWorkerToSearching()) {
              // Permission granted, proceed to steal.
              state = 2
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
              state = park()
            }
          }

        case 2 =>
          // First try to steal some expired timers:
          if (pool.stealTimers(System.nanoTime(), rnd)) {
            // some stolen timer created new work for us
            pool.transitionWorkerFromSearching(rnd)
            state = 4
          } else {
            // Try stealing fibers from other worker threads.
            val fiber = pool.stealFromOtherWorkerThread(index, rnd, self)
            if (fiber ne null) {
              // Successful steal. Announce that the current thread is no longer
              // looking for work.
              pool.transitionWorkerFromSearching(rnd)
              // Run the stolen fiber.
              try fiber.run()
              catch {
                case t if NonFatal(t) => pool.reportFailure(t)
                case t: Throwable => IOFiber.onFatalFailure(t)
              }
              // Transition to executing fibers from the local queue.
              state = 4
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
              state = park()
            }
          }

        case 3 =>
          // Check the external queue after a failed dequeue from the local
          // queue (due to the local queue being empty).
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
              case t if NonFatal(t) => pool.reportFailure(t)
              case t: Throwable => IOFiber.onFatalFailure(t)
            }

            // Transition to executing fibers from the local queue.
            state = 4
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
              case t if NonFatal(t) => pool.reportFailure(t)
              case t: Throwable => IOFiber.onFatalFailure(t)
            }

            // Transition to executing fibers from the local queue.
            state = 4
          } else {
            // Transition to stealing fibers from other `WorkerThread`s.
            // The permission is held implicitly by threads right after they
            // have been woken up.
            state = 2
          }

        case _ =>
          // Call all of our expired timers:
          val now = System.nanoTime()
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
              case t if NonFatal(t) => pool.reportFailure(t)
              case t: Throwable => IOFiber.onFatalFailure(t)
            }
            // Continue executing fibers from the local queue.
            state += 1
          } else {
            // Transition to checking the external queue.
            state = 1
          }
      }
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
   * A mechanism for executing support code before executing a blocking action.
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
   *
   * @note
   *   There is no reason to enclose any code in a `try/catch` block because the only way this
   *   code path can be exercised is through `IO.delay`, which already handles exceptions.
   */
  override def blockOn[T](thunk: => T)(implicit permission: CanAwait): T = {
    val rnd = random

    pool.notifyParked(rnd)

    if (blocking) {
      // This `WorkerThread` is already inside an enclosing blocking region.
      // There is no need to spawn another `WorkerThread`. Instead, directly
      // execute the blocking action.
      thunk
    } else {
      // Spawn a new `WorkerThread` to take the place of this thread, as the
      // current thread prepares to execute a blocking action.

      // We'll transfer our local queue to the new/cached thread;
      // don't forget to also transfer our cede bypass (if any):
      val bypass = cedeBypass
      if (bypass ne null) {
        queue.enqueue(bypass, external, random)
        cedeBypass = null
      }

      // Logically enter the blocking region.
      blocking = true

      val prefix = pool.blockerThreadPrefix
      // Set the name of this thread to a blocker prefixed name.
      setName(s"$prefix-$nameIndex")

      val cached = pool.cachedThreads.pollFirst()
      if (cached ne null) {
        // There is a cached worker thread that can be reused.
        val idx = index
        pool.replaceWorker(idx, cached)
        // Transfer the data structures to the cached thread and wake it up.
        cached.indexTransfer.transfer(idx)
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
        val idx = index
        val clone =
          new WorkerThread(idx, queue, parked, external, fiberBag, sleepers, pool)
        pool.replaceWorker(idx, clone)
        pool.blockedWorkerThreadCounter.incrementAndGet()
        clone.start()
      }

      thunk
    }
  }

  private[this] def init(newIdx: Int): Unit = {
    _index = newIdx
    queue = pool.localQueues(newIdx)
    sleepers = pool.sleepers(newIdx)
    parked = pool.parkedSignals(newIdx)
    fiberBag = pool.fiberBags(newIdx)

    // Reset the name of the thread to the regular prefix.
    val prefix = pool.threadPrefix
    setName(s"$prefix-$newIdx")
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
