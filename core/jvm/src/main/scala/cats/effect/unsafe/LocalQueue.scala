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
 * This code is a heavily adapted version of the `Local` queue from the `tokio`
 * runtime. The original source code in Rust is licensed under the MIT license
 * and available at:
 * https://docs.rs/crate/tokio/0.2.22/source/src/runtime/queue.rs.
 *
 * For the reasoning behind the design decisions of this code, please consult:
 * https://tokio.rs/blog/2019-10-scheduler#the-next-generation-tokio-scheduler.
 */

package cats.effect
package unsafe

import java.util.ArrayList
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Fixed length, FIFO, single producer, multiple consumer, lock-free, circular
 * buffer queue local to a single [[WorkerThread]].
 *
 * The queue supports exclusive write access **only** by the owner
 * [[WorkerThread]] to the `tail`, which represents the pointer for updating the
 * underlying `buffer` of [[cats.effect.IOFiber]] object references.
 *
 * The queue supports multi-threaded reading from the `head` pointer, both for
 * local dequeue operations by the owner [[WorkerThread]], as well as for work
 * stealing purposes by other contending [[WorkerThreads]].
 *
 * The code makes heavy use of `sun.misc.Unsafe` operations for relaxed atomic
 * operations and access to explicit memory fences for relaxed memory ordering
 * guarantees.
 *
 * General implementation details and notes (in order not to repeat them several
 * times in the code):
 *
 * 1. Loads with `plain` memory semantics are all direct accesses of
 *    non-volatile fields. These loads have absolutely no memory synchronization
 *    effects on their own, and the JIT compiler is completely free to instruct
 *    the CPU to reorder them for optimization purposes, cache the value or
 *    outright decide not to load them again. Therefore, these loads are **not**
 *    suitable for multi-threaded code. For more details, please consult the
 *    section explaining `plain` mode in the following amazing resource:
 *    http://gee.cs.oswego.edu/dl/html/j9mm.html#plainsec.
 *
 *    The `tail` of the queue is loaded with `plain` memory semantics whenever
 *    it is accessed from the owner [[WorkerThread]], since it is the only
 *    thread that can update the field, and therefore always has the most
 *    recent value, making memory synchronization unnecessary.
 *
 * 2. Loads with `acquire` memory semantics are achieved using an explicit
 *    `acquire` fence. These loads synchronize with any other store which has
 *    `release` semantics memory.
 *
 *    The `head` of the queue is **always** loaded using `acquire` memory
 *    semantics and stored using compare-and-swap operations which have (among
 *    other things) `release` memory semantics, so these actions synchronize and
 *    ensure proper publishing of value changes between threads.
 *
 *    The `tail` of the queue is loaded using `acquire` memory semantics
 *    whenever it is accessed from another [[WorkerThread]]. That way, the most
 *    recently published value of the tail is obtained and can be used to
 *    calculate the number of fibers available for stealing.
 *
 *    On the JVM, loads with `acquire` semantics are encoded as a `plain`
 *    (direct) load of a field, immediately followed by an `acquire` fence.
 *    For more details, please consult the section explaining `acquire/release`
 *    mode in the following amazing resource:
 *    http://gee.cs.oswego.edu/dl/html/j9mm.html#rasec.
 *    Newer JDK versions offer more sophisticated methods for controlling
 *    memory semantics of loads and stores, but since Scala is still heavily
 *    dependent on JDK8, explicit fences and `Unsafe` operations are the only
 *    way to encode these requirements.
 *
 * 3. Usage of `Unsafe.putOrderedInt` to update the value of the tail. This is
 *    a store with even more relaxed `release` semantics. It has been
 *    empirically shown that it can be successfully used in single producer
 *    environments, such as this queue. The value of the `tail` published with
 *    `putOrderedInt` can be detected by other threads using loads with
 *    `acquire` memory semantics, which is always the case in this code
 *    when other [[WorkerThread]]s load the value of the tail. All of the credit
 *    goes to Nitsan Wakart and their phenomenal blog post:
 *    http://psy-lob-saw.blogspot.com/2012/12/atomiclazyset-is-performance-win-for.html.
 *
 *    The post also contains more details on the exact relaxation of `require`
 *    semantics and the history behind `putOrderedInt`, with comments and quotes
 *    from Doug Lea.
 *
 *    For completeness, `Unsafe.putOrderedInt` is the implementation behind
 *    [[java.util.concurrent.atomic.AtomicInteger#lazySet]], making it available
 *    on JDK8 and above.
 *
 * 4. None of the previous points would have been possible in general. However,
 *    this code is JVM specific, and the JVM memory model **guarantees**
 *    atomicity of primitive stores up to 32 bits (including primitive
 *    integers).
 */
private final class LocalQueue extends LocalQueue.ClassPadding {

  // Static constants used throughout the code defined in Java source code in
  // order to insure inlining by the runtime.
  import LocalQueueConstants._

  /**
   * The array of [[cats.effect.IOFiber]] object references physically backing
   * the circular buffer queue.
   */
  private val buffer: Array[IOFiber[_]] = new Array(LocalQueueCapacity)

  /**
   * Preallocated [[java.util.ArrayList]] for native support of bulk add
   * operations to a [[java.util.concurrent.ConcurrentLinkedQueue]] when
   * offloading excess capacity from the local queue to the external queue. The
   * list must be cleared before each use.
   *
   * The list is initialized with the capacity of `HalfLocalQueueCapacity` + 1
   * because half of the local queue is offloaded to the external queue, as well
   * as the incoming 1 fiber from the [[LocalQueue#enqueue]] operation.
   */
  private[this] val overflow = new ArrayList[IOFiber[_]](HalfLocalQueueCapacity + 1)

  /**
   * Memory offset of the `head` field defined in [[LocalQueue$.Head]]
   * for carrying out `sun.misc.Unsafe` operations on the head of the queue. For
   * more details, please consult the documentation for the field at the end of
   * this file.
   */
  private[this] val headOffset: Long = {
    try {
      // Obtains a reference to the `head` field defined in the
      // `LocalQueue.Head` class.
      val field = classOf[LocalQueue.Head].getDeclaredField("head")
      // Calculates the memory offset for the field, a value which needs to be
      // passed to all `sun.misc.Unsafe` operations that operate on the `head`
      // field.
      Unsafe.objectFieldOffset(field)
    } catch {
      case t: Throwable =>
        // This code is run during class initialization, so any raised exception
        // is fatal. This should never be the case in practice because the Scala
        // standard library itself relies on the existence of `sun.misc.Unsafe`
        // in the JVM.
        throw new ExceptionInInitializerError(t)
    }
  }

  /**
   * Memory offset of the `tail` field defined in [[LocalQueue$.Tail]]
   * for carrying out `sun.misc.Unsafe` operations on the tail of the queue. For
   * more details, please consult the documentation for the field at the end of
   * this file.
   */
  private[this] val tailOffset: Long = {
    try {
      // Obtains a reference to the `head` field defined in the
      // `LocalQueue.Head` class.
      val field = classOf[LocalQueue.Tail].getDeclaredField("tail")
      // Calculates the memory offset for the field, a value which needs to be
      // passed to all `sun.misc.Unsafe` operations that operate on the `head`
      // field.
      Unsafe.objectFieldOffset(field)
    } catch {
      case t: Throwable =>
        // This code is run during class initialization, so any raised exception
        // is fatal. This should never be the case in practice because the Scala
        // standard library itself relies on the existence of `sun.misc.Unsafe`
        // in the JVM.
        throw new ExceptionInInitializerError(t)
    }
  }

  /**
   * Enqueues a fiber for execution at the back of this queue.
   *
   * @note Can **only** be correctly called by the owner [[WorkerThread]].
   *
   * There are three possible outcomes from the execution of this method:
   * 1. There is enough free capacity in this queue, taking care to
   *    account for a competing thread which is concurrently stealing
   *    from this queue, in which case the fiber will be pushed at the
   *    back of the queue.
   * 2. There is not enough free capacity and some other thread is
   *    concurrently stealing from this queue, in which case the fiber
   *    will be enqueued on the `external` queue.
   * 3. There is not enough free capacity in this queue and no other
   *    thread is stealing from it, in which case, half of this queue,
   *    including the new fiber will be spilled over and enqueued on
   *    the `external` queue as a bulk operation.
   *
   * @param fiber the fiber to be added to the local queue
   * @param external a reference to an external concurrent queue where excess
   *                 fibers can be enqueued in the case that there is not enough
   *                 capacity in the local queue
   */
  def enqueue(fiber: IOFiber[_], external: ConcurrentLinkedQueue[IOFiber[_]]): Unit = {
    // A plain, unsynchronized load of the tail of the local queue.
    val tl = tail

    // A CAS loop on the head of the queue. The loop can break out of the whole
    // method only when one of the three previously described outcomes has been
    // met.
    while (true) {
      // A load of the head of the queue using `acquire` semantics.
      val hd = head
      Unsafe.acquireFence()

      // Preparation for outcome 1, calculating the "steal" value of the head.
      val steal = msb(hd)
      if (unsignedShortSubtraction(tl, steal) < LocalQueueCapacity) {
        // Outcome 1, there is enough capacity in the queue for the incoming
        // fiber, regardless of the existence of a concurrent stealer. Proceed
        // to enqueue the fiber at the current tail index, update the tail
        // value, publish it for other threads and break out of the loop.
        buffer(tl & CapacityMask) = fiber
        Unsafe.putOrderedInt(this, tailOffset, unsignedShortAddition(tl, 1))
        return
      }

      // Preparation for outcome 2, calculating the "real" value of the head.
      val real = lsb(hd)
      if (steal != real) {
        // Outcome 2, there is a concurrent stealer and there is no available
        // capacity for the new fiber. Proceed to enqueue the fiber on the
        // external queue.
        external.offer(fiber)
        return
      }

      // Preparation for outcome 3.
      // There is no concurrent stealer, but there is also no leftover capacity
      // in the queue, to accept the incoming fiber. Time to transfer half of
      // the queue into the external queue. This is necessary because the next
      // fibers to be executed may spawn new fibers, which can quickly be
      // enqueued to the local queue, instead of always being delegated to the
      // external queue one by one.
      val realPlusHalf = unsignedShortAddition(real, HalfLocalQueueCapacity)
      val newHd = pack(realPlusHalf, realPlusHalf)
      if (Unsafe.compareAndSwapInt(this, headOffset, hd, newHd)) {
        // Outcome 3, half of the queue has been claimed by the owner
        // `WorkerThread`, to be transferred to the external queue.
        var i = 0
        // Transfer half of the local queue into the `overflow` list for a final
        // bulk add operation into the external queue. References in the local
        // queue are nulled out for garbage collection purposes.
        while (i < HalfLocalQueueCapacity) {
          val idx = unsignedShortAddition(real, i) & CapacityMask
          overflow.add(buffer(idx))
          buffer(idx) = null
          i += 1
        }
        // Also add the incoming fiber to the overflow list.
        overflow.add(fiber)
        // Enqueue all of the fibers to the external queue with a bulk add
        // operation.
        external.addAll(overflow)
        // Clear the overflow list before next use.
        overflow.clear()
        // The incoming fiber has been dealt with, break out of the loop.
        return
      }

      // None of the three final outcomes have been reached, loop again for a
      // fresh chance to enqueue the incoming fiber to the local queue, most
      // likely another thread has released spare capacity by stealing from the
      // queue.
    }
  }

  /**
   * Dequeues a fiber from the head of the local queue.
   *
   * @note Can **only** be correctly called by the owner [[WorkerThread]].
   *
   * The method returns only when it has successfully "stolen" from its own
   * local queue (even in the presence of a concurrent stealer), or the queue
   * itself is empty.
   *
   * Dequeueing even in the presence of a concurrent stealer works because
   * stealing is defined as movement of the "real" head of the queue even before
   * any fibers are taken. Dequeueing only operates on this value. Special care
   * needs to be taken to not overwrite the "steal" value in the presence of
   * a concurrent stealer.
   *
   * @return the fiber at the head of the queue, `null` if empty (to avoid
   *         unnecessary allocations)
   */
  def dequeue(): IOFiber[_] = {
    // A plain, unsynchronized load of the tail of the local queue.
    val tl = tail

    // A CAS loop on the head of the queue (since it is a FIFO queue). The loop
    // can break out of the whole method only when it has successfully moved
    // the head by 1 position, securing the fiber to return in the process.
    while (true) {
      // A load of the head of the queue using `acquire` semantics.
      val hd = head
      Unsafe.acquireFence()

      // Dequeuing only cares about the "real" value of the head.
      val real = lsb(hd)

      if (real == tl) {
        // The queue is empty, there is nothing to return.
        return null
      }

      // Calculate the new "real" value of the head.
      val newReal = unsignedShortAddition(real, 1)

      // Make sure to preserve the "steal" value in the presence of a concurrent
      // stealer. Otherwise, move the "steal" value along with the "real" one.
      val steal = msb(hd)
      val newHd = if (steal == real) pack(newReal, newReal) else pack(steal, newReal)

      if (Unsafe.compareAndSwapInt(this, headOffset, hd, newHd)) {
        // The head has been successfully moved forward and the fiber secured.
        // Proceed to null out the reference to the fiber and return it.
        val idx = real & CapacityMask
        val fiber = buffer(idx)
        buffer(idx) = null
        return fiber
      }
    }

    // Technically this is unreachable code. The only way to break out of the
    // loop is to return in the if statement. However, `while` loops evaluate
    // to `Unit` in Scala, which does not match the return type of the method,
    // so **something** has to be returned.
    null
  }

  /**
   * Steals half of the enqueued fibers from this queue and places them into
   * the destination [[LocalQueue]].
   *
   * @note Can **only** be correctly called by a concurrent [[WorkerThread]]
   *       which owns `dst`.
   *
   * Stealing is defined as calculating the half of the available fibers in the
   * local queue and moving the "real" value of the head, while keeping the
   * "steal" value where it is. This signals to other threads that the current
   * stealer has obtained exclusive access to the local queue and is in the
   * process of transferring fibers. All fibers between the "steal" and "real"
   * values are transfered, and finally, the "steal" value is updated to match
   * the "real" value of the head. To completely announce that the stealing
   * operation is done, the tail of the destination queue is published. This
   * prevents other threads from stealing from `dst` in the meantime.
   *
   * This contract allows the other methods to observe whenever a stealing
   * operation is ongoing and be extra careful not to step on the current state
   * of the stealing [[WorkerThread]].
   *
   * @param dst the destination local queue where the stolen fibers will end up
   * @return a reference to the first fiber to be executed by the stealing
   *         [[WorkerThread]].
   */
  def steal(dst: LocalQueue): IOFiber[_] = {
    // A plain, unsynchronized load of the tail of the destination queue, owned
    // by the executing thread.
    val dstTl = dst.tail

    // A load of the head of the destination queue using `acquire` semantics.
    val dstHd = dst.head
    Unsafe.acquireFence()

    // Before a steal is attempted, make sure that the destination queue is not
    // being stolen from. It can be argued that an attempt to steal fewer fibers
    // can be made here, but it is simpler to give up completely.
    val steal = msb(dstHd)
    if (unsignedShortSubtraction(dstTl, steal) > HalfLocalQueueCapacity) {
      return null
    }

    // A CAS loop on the head of the queue (since it is a FIFO queue). The loop
    // can break out of the whole method only when it has successfully moved
    // the head by `size / 2` positions, securing the fibers to transfer in the
    // process, or if the local queue is empty.
    while (true) {
      // A load of the head of the local queue using `acquire` semantics.
      var hd = head
      Unsafe.acquireFence()

      // Check whether another thread is already stealing from this local queue.
      val steal = msb(hd)
      val real = lsb(hd)

      if (steal != real) {
        // Another thread is stealing from this local queue. Nothing more to do.
        return null
      }

      val tl = tail
      Unsafe.acquireFence()

      // Calculate the size of the queue (the number of enqueued fibers).
      var n = unsignedShortSubtraction(tl, real)

      // Take half of them (+ 1). This helps in the situation when a
      // `WorkerThread` is completely blocked executing a blocking operation,
      // and all of its remaining fibers can be stolen.
      n = n - n / 2

      if (n == 0) {
        // There are no fibers to steal. Nothing more to do.
        return null
      }

      // Calculate the new "real" value of the head.
      var newReal = unsignedShortAddition(real, n)
      // Keep the old "steal" value, to signal that
      var newHd = pack(steal, newReal)

      if (Unsafe.compareAndSwapInt(this, headOffset, hd, newHd)) {
        // The head has been successfully moved forward and the stealing process
        // announced. Proceed to transfer all of the fibers between the old
        // "steal" and the new "real" values, nulling out the references for
        // garbage collection purposes.
        var i = 0
        while (i < n) {
          val srcIdx = unsignedShortAddition(steal, i) & CapacityMask
          dst.buffer(unsignedShortAddition(dstTl, i) & CapacityMask) = buffer(srcIdx)
          buffer(srcIdx) = null
          i += 1
        }

        // After transferring the stolen fibers, it is time to announce that
        // the stealing operation is done, by moving the "steal" value to match
        // the "real" value of the head. Opportunistically try to set it without
        // reading the `head` again.
        hd = newHd
        while (true) {
          newHd = pack(newReal, newReal)

          if (Unsafe.compareAndSwapInt(this, headOffset, hd, newHd)) {
            // The "steal" value now matches the "real" head value. Proceed to
            // return a fiber that can immediately be executed by the destnation
            // `WorkerThread`.
            n -= 1
            val newDstTl = unsignedShortAddition(dstTl, n)
            val fiber = dst.buffer(newDstTl & CapacityMask)

            if (n == 0) {
              // Only 1 fiber has been stolen. No need for any memory
              // synchronization operations.
              return fiber
            }

            // Publish the new tail of the destination queue. That way the
            // destination queue also becomes available for stealing.
            Unsafe.putOrderedInt(dst, tailOffset, newDstTl)
            return fiber
          } else {
            // Failed to opportunistically restore the value of the `head`. Read
            // it again and retry.
            hd = head
            Unsafe.acquireFence()
            newReal = lsb(hd)
          }
        }
      }
    }

    // Technically this is unreachable code. The only way to break out of the
    // loop is to return in the if statement. However, `while` loops evaluate
    // to `Unit` in Scala, which does not match the return type of the method,
    // so **something** has to be returned.
    null
  }

  /**
   * Checks whether the local queue is empty.
   *
   * @return `true` if the queue is empty, `false` otherwise.
   */
  def isEmpty(): Boolean = {
    val tl = tail
    val hd = head
    Unsafe.acquireFence()
    lsb(hd) == tl
  }

  /**
   * Checks whether the local queue is **not** empty.
   *
   * @return `true` if the queue is **not** empty, `false` otherwise.
   */
  def nonEmpty(): Boolean =
    !isEmpty()

  /**
   * Extracts the 16 least significant bits from a 32 bit integer value.
   *
   * @param n the integer value (usually the head of the queue)
   * @return the 16 least significant bits as an integer value
   */
  private[this] def lsb(n: Int): Int =
    n & UnsignedShortMask

  /**
   * Extracts the 16 most significant bits from a 32 bit integer value.
   *
   * @param n the integer value (usually the head of the queue)
   * @return the 16 most significant bits as an integer value
   */
  private[this] def msb(n: Int): Int =
    n >>> 16

  /**
   * Concatenates two integer values which represent the most significant and
   * least significant 16 bits respectively, into a single 32 bit integer value.
   *
   * @param msb an integer value that represents the 16 most significant bits
   * @param lsb an integer value that represents the 16 least significant bits
   * @return a 32 bit integer value which is a concatenation of the input values
   */
  private[this] def pack(msb: Int, lsb: Int): Int =
    (msb << 16) | lsb

  /**
   * Encodes addition of unsigned 16 bit values as an operation on 32 bit
   * integers. After performing the addition, only the 16 least significant bits
   * are returned.
   *
   * @param x the augend (first operand)
   * @param y the addend (second operand)
   * @return the unsigned 16 bit sum as a 32 bit integer value
   */
  private[this] def unsignedShortAddition(x: Int, y: Int): Int =
    lsb(x + y)

  /**
   * Encodes subtraction of 16 bit values as an operation on 32 bit integers.
   * After performing the subtraction, only the 16 least significant bits are
   * returned.
   *
   * @param x the minuend
   * @param y the subtrahend
   * @return the unsigned 16 bit difference as a 32 bit integer value
   */
  private[this] def unsignedShortSubtraction(x: Int, y: Int): Int =
    lsb(x - y)
}

/**
 * The [[LocalQueue$]] companion object contains a set of abstract classes which
 * are used to define padding between the `head` and `tail` fields of the
 * [[LocalQueue]], such that these fields always fall on different cache lines,
 * to avoid "false sharing", a phenomenon that occurs when different threads
 * modify one of the fields, which would cause any other thread to have to
 * reload both fields from memory, even when the value of one of the fields has
 * not necessarily changed in the meantime. CPUs always load memory in cache
 * line sized chunks.
 *
 * The reason this chain of classes exists is because the JVM is free to reorder
 * class fields into memory as it sees fit for optimization purposes. However,
 * there is a rule which states that the fields of parent classes **always**
 * come before the fields of the child class. Therefore, by carefully arranging
 * a chain of child classes, the right memory layout can be achieved.
 */
private object LocalQueue {

  /**
   * The initial class in the chain, which defines 16 fields of type `Long`.
   * These fields take up 16 * 8 = 128 bytes of contiguous memory, which is the
   * size of a single cache line of modern processors.
   */
  abstract class HeadPadding {
    protected val phead00: Long = 0
    protected val phead01: Long = 0
    protected val phead02: Long = 0
    protected val phead03: Long = 0
    protected val phead04: Long = 0
    protected val phead05: Long = 0
    protected val phead06: Long = 0
    protected val phead07: Long = 0
    protected val phead08: Long = 0
    protected val phead09: Long = 0
    protected val phead10: Long = 0
    protected val phead11: Long = 0
    protected val phead12: Long = 0
    protected val phead13: Long = 0
    protected val phead14: Long = 0
    protected val phead15: Long = 0
  }

  /**
   * The second class in the chain, whose `head` field comes after the initial
   * 128 bytes of padding.
   */
  abstract class Head extends HeadPadding {

    /**
     * The head of the queue.
     *
     * Concurrently updated by many [[WorkerThread]]s.
     *
     * Conceptually, it is a concatenation of two unsigned 16 bit values. Since
     * the capacity of the local queue is less than (2^16 - 1), the extra values
     * are used to distinguish between the case where the queue is empty
     * (`head` == `tail`) and (`head` - `tail` == `LocalQueueCapacity`), which
     * is an important distinction for other [[WorkerThread]]s trying to steal
     * work from this queue.
     *
     * The least significant 16 bits of the integer value represent the "real"
     * value of the head, pointing to the next [[cats.effect.IOFiber]] instance
     * to be dequeued from the queue.
     *
     * The most significant 16 bits of the integer value represent the "steal"
     * value of the head. This value is altered by another [[WorkerThread]]
     * which has managed to win the race and become the exclusive "stealer" from
     * this queue. During the period in which the "steal" value is different
     * from the "real" value, no other [[WorkerThread]] can steal from this
     * queue, and the owning [[WorkerThread]] also takes special care to not
     * step over this effort. The stealer [[WorkerThread]] is free to transfer
     * half of the available [[cats.effect.IOFiber]] object references from this
     * queue into its own [[LocalQueue]] during this period, making sure to undo
     * the changes to the "steal" value of the head after completion.
     *
     * This field is not marked as `volatile`, but is instead manipulated
     * exclusively using `sun.misc.Unsafe` operations, which help avoid the
     * unnecessarily strict memory ordering guarantees that come from volatility
     * on the JVM, while still allowing for properly synchronized multi-threaded
     * updates to the value.
     */
    protected var head: Int = 0
  }

  /**
   * The third class in the chain, defines 128 bytes of padding which comes
   * between the `head` and the `tail` fields.
   */
  abstract class TailPadding extends Head {
    protected val ptail00: Long = 0
    protected val ptail01: Long = 0
    protected val ptail02: Long = 0
    protected val ptail03: Long = 0
    protected val ptail04: Long = 0
    protected val ptail05: Long = 0
    protected val ptail06: Long = 0
    protected val ptail07: Long = 0
    protected val ptail08: Long = 0
    protected val ptail09: Long = 0
    protected val ptail10: Long = 0
    protected val ptail11: Long = 0
    protected val ptail12: Long = 0
    protected val ptail13: Long = 0
    protected val ptail14: Long = 0
    protected val ptail15: Long = 0
  }

  /**
   * The fourth class in the chain, whose `tail` field comes after the middle
   * 128 bytes of padding.
   */
  abstract class Tail extends TailPadding {

    /**
     * The tail of the queue.
     *
     * Only ever updated by the owner [[WorkerThread]], but also read by other
     * threads to determine the current size of the queue, for work stealing
     * purposes. Denotes the next available free slot in the `buffer` arrray.
     *
     * Conceptually, it is an unsigned 16 bit value (the most significant 16
     * bits of the integer value are ignored in most operations). Since the
     * capacity of the local queue is less than (2^16 - 1), the extra values
     * are used to distinguish between the case where the queue is empty
     * (`head` == `tail`) and (`head` - `tail` == `LocalQueueCapacity`), which
     * is an important distinction for other [[WorkerThread]]s trying to steal
     * work from this queue.
     *
     * This field is not marked as `volatile`, but is instead manipulated
     * exclusively using `sun.misc.Unsafe` operations, which help avoid the
     * unnecessarily strict memory ordering guarantees that come from volatility
     * on the JVM, while still allowing for properly publishing the value of the
     * field for synchronized multi-threaded read access.
     */
    protected var tail: Int = 0
  }

  /**
   * The fifth and final class in the chain, defines additional 128 bytes of
   * padding between the `tail` field and the rest of the class fields in
   * [[LocalQueue]]. This ensures that changes to the `tail` will not be falsely
   * shared with the fields of the queue and vice-versa.
   */
  abstract class ClassPadding extends Tail {
    protected val pclss00: Long = 0
    protected val pclss01: Long = 0
    protected val pclss02: Long = 0
    protected val pclss03: Long = 0
    protected val pclss04: Long = 0
    protected val pclss05: Long = 0
    protected val pclss06: Long = 0
    protected val pclss07: Long = 0
    protected val pclss08: Long = 0
    protected val pclss09: Long = 0
    protected val pclss10: Long = 0
    protected val pclss11: Long = 0
    protected val pclss12: Long = 0
    protected val pclss13: Long = 0
    protected val pclss14: Long = 0
    protected val pclss15: Long = 0
  }
}
