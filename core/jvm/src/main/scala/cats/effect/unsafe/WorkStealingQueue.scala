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

/*
 * This code is an adaptation of the `Local` queue from the `tokio` runtime.
 * The original source code in Rust is licensed under the MIT license and available
 * at: https://docs.rs/crate/tokio/0.2.22/source/src/runtime/queue.rs.
 *
 * For the reasoning behind the design decisions of this code, please consult:
 * https://tokio.rs/blog/2019-10-scheduler#the-next-generation-tokio-scheduler.
 */

package cats.effect
package unsafe

import java.util.ArrayList
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Fixed length, double ended, singe producer, multiple consumer queue local to a
 * single `WorkerThread` that supports single threaded updates to the tail of the
 * queue (to be executed **only** by the owner worker thread) and multi threaded
 * updates to the head (local dequeueing or inter thread work stealing).
 */
private final class WorkStealingQueue extends WorkStealingQueue.TailPadding {

  import WorkStealingQueueConstants._

  /**
   * Holds the scheduled fibers.
   */
  private val buffer: Array[IOFiber[_]] = new Array(LocalQueueCapacity)

  private[this] val headOffset: Long = {
    try {
      val field = classOf[WorkStealingQueue.Head].getDeclaredField("head")
      Unsafe.objectFieldOffset(field)
    } catch {
      case t: Throwable =>
        throw new ExceptionInInitializerError(t)
    }
  }

  private[this] val tailOffset: Long = {
    try {
      val field = classOf[WorkStealingQueue.Tail].getDeclaredField("tail")
      Unsafe.objectFieldOffset(field)
    } catch {
      case t: Throwable =>
        throw new ExceptionInInitializerError(t)
    }
  }

  /**
   * Returns true if there are no enqueued fibers.
   */
  def isEmpty(): Boolean = {
    val hd = Unsafe.getInt(this, headOffset)
    val tl = Unsafe.getInt(this, tailOffset)
    Unsafe.acquireFence()
    lsb(hd) == tl
  }

  /**
   * Returns true if there are enqueued fibers available for stealing.
   */
  def isStealable(): Boolean =
    !isEmpty()

  /**
   * Enqueues a fiber for execution at the back of this queue. Should
   * **only** be called by the owner worker thread.
   *
   * There are three possible outcomes from the execution of this method:
   * 1. There is enough free capacity in this queue, regardless if
   *    another thread is concurrently stealing from this queue, in
   *    which case the fiber will be pushed at the back of the queue.
   * 2. There is not enough free capacity and some other thread is
   *    concurrently stealing from this queue, in which case the fiber
   *    will be enqueued on the `external` queue.
   * 3. There is not enough free capacity in this queue and no other
   *    thread is stealing from it, in which case, half of this queue,
   *    including the new fiber will be spilled over and enqueued on
   *    the `external` queue as a linked batch of fibers.
   */
  def enqueue(fiber: IOFiber[_], external: ConcurrentLinkedQueue[IOFiber[_]]): Unit = {
    var hd = 0
    val tl = Unsafe.getInt(this, tailOffset)

    var cont = true
    while (cont) {
      hd = Unsafe.getInt(this, headOffset)
      Unsafe.acquireFence()

      val steal = msb(hd) // Check if a thread is concurrently stealing from this queue.
      val real = lsb(hd) // Obtain the real head of the queue.

      if (unsignedShortSubtraction(tl, steal) < LocalQueueCapacity) {
        // There is free capacity for the fiber, proceed to enqueue it.
        cont = false
      } else if (steal != real) {
        // Another thread is concurrently stealing, so push the new
        // fiber onto the external queue and return.
        external.offer(fiber)
        return
      } else {
        // There is no free capacity for the fiber and no one else is stealing from this queue.
        // Overflow half of this queue and the new fiber into the external queue.
        if (overflowToExternal(fiber, real, external)) {
          return
        }

        // Failed to enqueue the back half of this queue on the external queue. Retrying.
      }
    }

    // Enqueue the new fiber for later execution.

    // Map the position to a slot index.
    val idx = tl & CapacityMask
    // Write the fiber to the slot.
    buffer(idx) = fiber
    // Make the fiber available.

    val next = unsignedShortAddition(tl, 1)
    Unsafe.releaseFence()
    Unsafe.putInt(this, tailOffset, next)
  }

  private[this] def overflowToExternal(
      fiber: IOFiber[_],
      hd: Int,
      external: ConcurrentLinkedQueue[IOFiber[_]]): Boolean = {
    val prev = pack(hd, hd)

    // Claim half of the fibers.
    // We are claiming the fibers **before** reading them out of the buffer.
    // This is safe because only the **current** thread is able to push new
    // fibers.
    val headPlusHalf = unsignedShortAddition(hd, HalfLocalQueueCapacity)
    if (!Unsafe.compareAndSwapInt(this, headOffset, prev, pack(headPlusHalf, headPlusHalf))) {
      // We failed to claim the fibers, losing the race. Return out of
      // this function and try the full `enqueue` method again. The queue
      // may not be full anymore.
      return false
    }

    // We have successfully claimed the fibers. Continue with linking them
    // and moving them to the external queue.

    var i = 0 // loop counter
    var iIdx = 0 // mapped index
    val list = new ArrayList[IOFiber[_]](HalfLocalQueueCapacity + 1)
    while (i < HalfLocalQueueCapacity) {
      // Map the loop counter to an index.
      iIdx = unsignedShortAddition(i, hd) & CapacityMask

      list.add(buffer(iIdx))
      i += 1
    }
    list.add(fiber)

    // Push the fibers onto the external queue starting from the head fiber
    // and ending with the new fiber.
    external.addAll(list)
    true
  }

  /**
   * Dequeue a fiber from the local queue. Returns `null` if the queue is empty.
   * Should **only** be called by the owner worker thread.
   */
  def dequeueLocally(): IOFiber[_] = {
    var hd = 0
    var idx = 0 // will contain the index of the fiber to be dequeued
    var steal = 0 // will contain a concurrent stealer
    var real = 0 // will contain the real head of the queue
    var nextReal = 0 // one after the real head of the queue
    var nextHead = 0 // will contain the next full head

    val tl = Unsafe.getInt(this, tailOffset)

    var cont = true
    while (cont) {
      hd = Unsafe.getInt(this, headOffset)
      Unsafe.acquireFence()

      steal = msb(hd) // Check if a thread is concurrently stealing from this queue.
      real = lsb(hd) // Obtain the real head of the queue.

      if (real == tl) {
        // The queue is empty. There is nothing to be dequeued. Return.
        return null
      }

      nextReal = unsignedShortAddition(real, 1)

      nextHead = if (steal == real) {
        // There are no concurrent threads stealing from this queue. Both `steal` and `real`
        // values should be updated.
        pack(nextReal, nextReal)
      } else {
        // There is a thread concurrently stealing from this queue. Do not mess with its
        // steal tag value, only update the real head.
        pack(steal, nextReal)
      }

      // Attempt to claim a fiber.
      if (Unsafe.compareAndSwapInt(this, headOffset, hd, nextHead)) {
        // Successfully claimed the fiber to be dequeued.
        // Map to its index and break out of the loop.
        idx = real & CapacityMask
        cont = false
      } else {
        // Failed to claim the fiber to be dequeued. Retry.
      }
    }

    // Dequeue the claimed fiber.
    val fiber = buffer(idx)
    // Remove the reference to the fiber so that it can be cleaned up.
    // This is safe to do as only the owner worker thread can push new fibers
    // and it is currently dequeing a fiber, not pushing.
    buffer(idx) = null
    fiber
  }

  /**
   * Steal half of the enqueued fibers from this queue and place them into
   * the destination `WorkStealingQueue`. Executed by a concurrent thread
   * which owns `dst`. Returns the first fiber to be executed.
   */
  def stealInto(dst: WorkStealingQueue): IOFiber[_] = {
    val dstTail = Unsafe.getInt(dst, tailOffset)

    // To the caller, `dst` may **look** empty but still have values
    // contained in the buffer. If another thread is concurrently stealing
    // from `dst` there may not be enough capacity to steal.
    val dstHead = Unsafe.getInt(dst, headOffset)
    Unsafe.acquireFence()

    // Check if a thread is concurrently stealing from the destination queue.
    val steal = msb(dstHead)

    if (unsignedShortSubtraction(dstTail, steal) > HalfLocalQueueCapacity) {
      // We *could* try to steal fewer fibers here, but for simplicity, we're just
      // going to abort.
      return null
    }

    // Steal half of the current number of fibers.
    var n = internalStealInto(dst, dstTail)

    if (n == 0) {
      // No fibers were stolen. Return.
      return null
    }

    // We are returning the first fiber.
    n -= 1

    // Confirm the steal by moving the tail of the destination queue.
    val retPos = unsignedShortAddition(dstTail, n)

    // Map the index of the fiber to be returned.
    val retIdx = retPos & CapacityMask

    // Get the fiber to be returned. This is safe to do because the
    // fiber has already been written by `internalStealInto` but the
    // tail has still not been published.
    val ret = dst.buffer(retIdx)

    if (n == 0) {
      // No need for arithmetic and volatile updates. We are immediately
      // returning the 1 stolen fiber.
      return ret
    }

    val tl = unsignedShortAddition(dstTail, n)
    Unsafe.releaseFence()
    Unsafe.putInt(dst, tailOffset, tl)
    ret
  }

  /**
   * Steal half of the enqueued fibers from this queue and move them into
   * the destination `WorkStealingQueue`. Executed by a concurrent thread
   * which owns `dst`. Returns the number of moved fibers.
   */
  private[this] def internalStealInto(dst: WorkStealingQueue, dstTail: Int): Int = {
    var prevPacked = 0
    var nextPacked = 0
    var prevPackedSteal =
      0 // will hold information on a thread that concurrently steals from the source queue
    var prevPackedReal = 0 // will hold the real head of the source queue
    var srcTail = 0 // will hold the tail of the source queue

    var n = 0 // will hold the number of stolen fibers

    var cont = true
    while (cont) {
      prevPacked = Unsafe.getInt(this, headOffset)
      srcTail = Unsafe.getInt(this, tailOffset)
      Unsafe.acquireFence()

      prevPackedSteal = msb(prevPacked)
      prevPackedReal = lsb(prevPacked)

      if (prevPackedSteal != prevPackedReal) {
        // Another thread is concurrently stealing from the source queue. Do not proceed.
        return 0
      }

      // Number of available fibers to steal.
      n = unsignedShortSubtraction(srcTail, prevPackedReal)

      // Stealing half of them.
      n = n - n / 2

      if (n == 0) {
        // No fibers available to steal. Return.
        return 0
      }

      // Update the real head index to acquire the tasks.
      val stealTo = unsignedShortAddition(prevPackedReal, n)
      nextPacked = pack(prevPackedSteal, stealTo)

      // Claim all those fibers. This is done by incrementing the "real"
      // head but not the "steal". By doing this, no other thread is able to
      // steal from this queue until the current thread completes.
      // Will update the "steal" after moving the fibers, when the steal
      // is fully complete.
      if (Unsafe.compareAndSwapInt(this, headOffset, prevPacked, nextPacked)) {
        // Successfully claimed the fibers. Breaking out of the loop.
        cont = false
      } else {
        // Failed to claim the fibers. Retrying.
      }
    }

    // The offset into the source queue.
    val offset = msb(nextPacked)
    var srcPos = 0 // will contain the position in the source queue
    var dstPos = 0 // will contain the position in the destination queue
    var srcIdx = 0 // will contain the index in the source queue
    var dstIdx = 0 // will contain the index in the destination queue
    var fiber: IOFiber[_] = null // placeholder mutable fiber pointer for moving fibers

    // Take all the fibers.
    var i = 0
    while (i < n) {
      // Compute the positions.
      srcPos = unsignedShortAddition(offset, i)
      dstPos = unsignedShortAddition(dstTail, i)

      // Map to indices.
      srcIdx = srcPos & CapacityMask
      dstIdx = dstPos & CapacityMask

      // Obtain the fiber to be moved.
      // This is safe to do because the fiber has been already claimed using the atomic operation above.
      fiber = buffer(srcIdx)
      // Remove the reference to the fiber so that it can be cleaned up.
      // This is safe to do since this worker thread has claimed the whole queue in order to steal from it.
      // The owner worker thread is able to detect this and falls back to enqueueing a new fiber to the external queue.
      buffer(srcIdx) = null

      // Move the fiber to the destination queue.
      // This is safe to do because this method is executed on the thread which owns the
      // destination queue, making it the only allowed producer.
      dst.buffer(dstIdx) = fiber

      i += 1
    }

    // Fully publish the steal from the source queue. Remove the current
    // thread as the stealer of this queue.
    cont = true
    while (cont) {
      prevPacked = Unsafe.getInt(this, headOffset)
      Unsafe.acquireFence()

      // Compute the new head.
      val hd = lsb(prevPacked)
      nextPacked = pack(hd, hd)

      if (Unsafe.compareAndSwapInt(this, headOffset, prevPacked, nextPacked)) {
        // Successfully published the new head of the source queue. Done.
        cont = false
      } else {
        // Failed to publish the new head of the source queue. Retry.
      }
    }

    n
  }

  /**
   * Extract the 16 least significant bits from a 32 bit integer.
   */
  private[this] def lsb(n: Int): Int =
    n & UnsignedShortMask

  /**
   * Extract the 16 most significant bits from a 32 bit integer.
   */
  private[this] def msb(n: Int): Int =
    n >>> 16

  /**
   * Pack two unsigned 16 bit integers into a single 32 bit integer.
   */
  private[this] def pack(x: Int, y: Int): Int =
    y | (x << 16)

  /**
   * Unsigned 16 bit addition.
   */
  private[this] def unsignedShortAddition(x: Int, y: Int): Int =
    lsb(x + y)

  /**
   * Unsigned 16 bit subtraction.
   */
  private[this] def unsignedShortSubtraction(x: Int, y: Int): Int =
    lsb(x - y)
}

private object WorkStealingQueue {
  abstract class InitPadding {
    protected var pinit00: Long = _
    protected var pinit01: Long = _
    protected var pinit02: Long = _
    protected var pinit03: Long = _
    protected var pinit04: Long = _
    protected var pinit05: Long = _
    protected var pinit06: Long = _
    protected var pinit07: Long = _
    protected var pinit08: Long = _
    protected var pinit09: Long = _
    protected var pinit10: Long = _
    protected var pinit11: Long = _
    protected var pinit12: Long = _
    protected var pinit13: Long = _
    protected var pinit14: Long = _
    protected var pinit15: Long = _
  }

  abstract class Head extends InitPadding {

    /**
     * Concurrently updated by many threads.
     *
     * Contains two unsigned 16 bit values. The LSB bytes are the "real" head of the queue.
     * The unsigned 16 bytes in the MSB are set by a stealer in process of stealing values.
     * It represents the first value being stolen in the batch. Unsigned 16 bit integer is
     * used in order to distinguish between `head == tail` and `head == tail - capacity`.
     *
     * When both unsigned 16 bit balues are the same, there is no active stealer.
     *
     * Tracking an in-progress stealer prevents a wrapping scenario.
     */
    @volatile protected var head: Int = 0
  }

  abstract class HeadPadding extends Head {
    protected var phead00: Long = _
    protected var phead01: Long = _
    protected var phead02: Long = _
    protected var phead03: Long = _
    protected var phead04: Long = _
    protected var phead05: Long = _
    protected var phead06: Long = _
    protected var phead07: Long = _
    protected var phead08: Long = _
    protected var phead09: Long = _
    protected var phead10: Long = _
    protected var phead11: Long = _
    protected var phead12: Long = _
    protected var phead13: Long = _
    protected var phead14: Long = _
    protected var phead15: Long = _
  }

  abstract class Tail extends HeadPadding {

    /**
     * Only updated by the owner worker thread, but read by many threads.
     *
     * Represents an unsigned 16 bit value.
     */
    @volatile protected var tail: Int = 0
  }

  abstract class TailPadding extends Tail {
    protected var ptail00: Long = _
    protected var ptail01: Long = _
    protected var ptail02: Long = _
    protected var ptail03: Long = _
    protected var ptail04: Long = _
    protected var ptail05: Long = _
    protected var ptail06: Long = _
    protected var ptail07: Long = _
    protected var ptail08: Long = _
    protected var ptail09: Long = _
    protected var ptail10: Long = _
    protected var ptail11: Long = _
    protected var ptail12: Long = _
    protected var ptail13: Long = _
    protected var ptail14: Long = _
    protected var ptail15: Long = _
  }
}
