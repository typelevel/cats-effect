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
 * This code is a heavily adapted version of the `Local` queue from the `tokio`
 * runtime. The original source code in Rust is licensed under the MIT license
 * and available at:
 * https://docs.rs/crate/tokio/0.2.22/source/src/runtime/queue.rs.
 *
 * For more details behind the design decisions of that queue implementation,
 * please consult:
 * https://tokio.rs/blog/2019-10-scheduler#the-next-generation-tokio-scheduler.
 */

package cats.effect
package unsafe

import cats.effect.tracing.TracingConstants

import java.util.concurrent.ThreadLocalRandom

/**
 * Fixed length, FIFO, single producer, multiple consumer, lock-free, circular buffer queue
 * local to a single [[WorkerThread]].
 *
 * The queue supports exclusive write access '''only''' by the owner [[WorkerThread]] to the
 * `tail`, which represents the pointer for updating the underlying `buffer` of
 * [[java.lang.Runnable]] object references.
 *
 * The queue supports multi-threaded reading from the `head` pointer, both for local dequeue
 * operations by the owner [[WorkerThread]], as well as for work stealing purposes by other
 * contending [[WorkerThread]] s.
 *
 * The synchronization is achieved through atomic operations on
 * [[java.util.concurrent.atomic.AtomicInteger]] (used for representing the `head` and the
 * `tail` pointers).
 *
 * The code makes heavy use of the [[java.util.concurrent.atomic.AtomicInteger#lazySet]] atomic
 * operation to achieve relaxed memory ordering guarantees when compared to a JVM `volatile`
 * variable.
 *
 * General implementation details and notes:
 *
 *   1. Loads with ''plain'' memory semantics are all direct accesses of non-volatile fields.
 *      These loads have absolutely no memory synchronization effects on their own, and the JIT
 *      compiler is completely free to instruct the CPU to reorder them for optimization
 *      purposes, cache the value or outright decide not to load them again. Therefore, these
 *      loads are '''not''' suitable for multi-threaded code. For more details, please consult
 *      the section explaining ''plain'' mode in the following amazing resource on the
 *      [[http://gee.cs.oswego.edu/dl/html/j9mm.html#plainsec Java 9+ memory model]].
 *
 * The `tail` of the queue is loaded with ''plain'' memory semantics whenever it is accessed by
 * the owner [[WorkerThread]], since it is the only thread that is allowed to update the field,
 * and therefore always has the most recent value, making memory synchronization unnecessary.
 *
 * ''Plain'' loads of `volatile` or [[java.util.concurrent.atomic.AtomicInteger]] are
 * unfortunately unavailable on Java 8. These however are simulated with a specialized subclass
 * of [[java.util.concurrent.atomic.AtomicInteger]] which additionally keeps a non-volatile
 * field which the owner [[WorkerThread]] reads in order to avoid ''acquire'' memory barriers
 * which are unavoidable when reading a `volatile` field or using
 * [[java.util.concurrent.atomic.AtomicInteger#get]].
 *
 * 2. Loads with ''acquire'' memory semantics are achieved on the JVM either by a direct load of
 * a `volatile` field or by using [[java.util.concurrent.atomic.AtomicInteger#get]].
 *
 * The `head` of the queue is '''always''' loaded using ''acquire'' memory semantics and stored
 * using compare-and-swap operations which provide (among other properties) ''release'' memory
 * semantics, the synchronizing counterpart which ensures proper publishing of memory location
 * changes between threads.
 *
 * The `tail` of the queue is loaded using ''acquire'' memory semantics whenever it is accessed
 * from another [[WorkerThread]]. That way, the most recently published value of the tail is
 * obtained and can be used to calculate the number of fibers available for stealing.
 *
 * For more details, please consult the section explaining ''acquire/release'' mode in the
 * following amazing resource on the
 * [[http://gee.cs.oswego.edu/dl/html/j9mm.html#plainsec Java 9+ memory model]].
 *
 * 3. Stores with ''release'' memory semantics are achieved on the JVM using the
 * [[java.util.concurrent.atomic.AtomicInteger#lazySet]] operation. Prior to Java 9, this is a
 * unique, underdocumented and often misunderstood operation. It is a `volatile` write without a
 * full memory fence, allowing for much higher throughput under heavy contention, provided that
 * it is used in a single producer environment, such as this queue. The value of the `tail`
 * published using ''release'' semantics can be properly detected by other threads using loads
 * with ''acquire'' memory semantics, which is always the case whenever non-owner
 * [[WorkerThread]] s load the value of the tail of another [[WorkerThread]] 's local queue. All
 * of the credit for this invaluable discovery goes to Nitsan Wakart and their phenomenal
 * [[http://psy-lob-saw.blogspot.com/2012/12/atomiclazyset-is-performance-win-for.html blog post]].
 *
 * The post also contains more details on the fascinating history of
 * [[java.util.concurrent.atomic.AtomicInteger#lazySet]], with comments and quotes from Doug
 * Lea.
 *
 * 4. Even though usage of `sun.misc.Unsafe` in these classes was heavily experimented with, it
 * was decided to ultimately settle on standard Java APIs. We believe that being a good JVM
 * citizen (using official APIs), making it easier for users to create GraalVM native images and
 * having generally more maintainable code vastly outweigh the marginal improvements to
 * performance that `Unsafe` would bring. The inherent contention that arises under thread
 * synchronization in the multi-threaded cats-effect runtime is still much more costly such that
 * the performance gains with `Unsafe` pale in comparison. We have found that algorithm
 * improvements and smarter data structures bring much larger performance gains.
 *
 * Should a discovery be made which proves that direct usage of `Unsafe` brings dramatic
 * improvements in performance, this decision to not use it might be reversed. This is however,
 * very unlikely, as [[java.util.concurrent.atomic.AtomicInteger]] is just a thin wrapper around
 * `Unsafe`. And `Unsafe` is only really needed on JVM 8. JVM 9+ introduce much richer and
 * better APIs and tools for building high-performance concurrent systems (e.g. `VarHandle`).
 */
private final class LocalQueue extends LocalQueuePadding {

  import LocalQueueConstants._
  import TracingConstants._

  /**
   * The array of [[java.lang.Runnable]] object references physically backing the circular
   * buffer queue.
   */
  private[this] val buffer: Array[Runnable] = new Array(LocalQueueCapacity)

  /*
   * What follows is a collection of counters exposed through the
   * `cats.effect.unsafe.metrics.LocalQueueSamplerMBean` interface.
   */

  /**
   * A running counter of the number of fibers enqueued on this [[LocalQueue]] during the
   * lifetime of the queue. This variable is published through the `tail` of the queue. In order
   * to observe the latest value, the `tailPublisher` atomic field should be loaded first.
   */
  private[this] var totalFiberCount: Long = 0

  /**
   * A running counter of the number of fibers spilt over from this [[LocalQueue]] into the
   * external queue during the lifetime of the queue. This variable is published through the
   * `tail` of the queue. In order to observe the latest value, the `tailPublisher` atomic field
   * should be loaded first.
   */
  private[this] var totalSpilloverCount: Long = 0

  /**
   * A running counter of the number of successful steal attempts by other [[WorkerThread]] s
   * during the lifetime of the queue. This variable is published through the `head` of the
   * queue. In order to observe the latest value, the `head` atomic field should be loaded
   * first.
   */
  private[this] var successfulStealAttemptCount: Long = 0

  /**
   * A running counter of the number of fibers stolen by other [[WorkerThread]] s during the
   * lifetime of the queue. This variable is published through the `head` of the queue. In order
   * to observe the latest value, the `head` atomic field should be loaded first.
   */
  private[this] var stolenFiberCount: Long = 0

  /**
   * Enqueues a fiber for execution at the back of this queue.
   *
   * @note
   *   Can '''only''' be correctly called by the owner [[WorkerThread]].
   *
   * There are three possible outcomes from the execution of this method:
   *
   *   1. There is enough free capacity in this queue, taking care to account for a competing
   *      thread which is concurrently stealing from this queue, in which case the fiber will be
   *      added to the back of the queue.
   *
   *   1. There is not enough free capacity and some other thread is concurrently stealing from
   *      this queue, in which case the fiber will be enqueued on the `external` queue.
   *
   *   1. There is not enough free capacity in this queue and no other thread is stealing from
   *      it, in which case, half of this queue, including the new fiber will be offloaded to
   *      the `batched` queue as a bulk operation.
   *
   * @param fiber
   *   the fiber to be added to the local queue
   * @param external
   *   a reference to a striped concurrent queue where excess fibers can be enqueued (either
   *   individually or in batches) in the case that there is not enough leftover capacity in the
   *   local queue
   * @param random
   *   a reference to an uncontended source of randomness, to be passed along to the striped
   *   concurrent queues when executing their enqueue operations
   */
  def enqueue(fiber: Runnable, external: ScalQueue[AnyRef], random: ThreadLocalRandom): Unit = {
    // A plain, unsynchronized load of the tail of the local queue.
    val tl = tail

    // A CAS loop on the head of the queue. The loop can break out of the whole
    // method only when one of the three previously described outcomes has been
    // observed.
    while (true) {
      // A load of the head of the queue using `acquire` semantics.
      val hd = Head.updater.get(this)

      // Preparation for outcome 1, calculating the "steal" tag of the head.
      val steal = msb(hd)
      if (unsignedShortSubtraction(tl, steal) < LocalQueueCapacity) {
        // Outcome 1, there is enough capacity in the queue for the incoming
        // fiber, regardless of the existence of a concurrent stealer. Proceed
        // to enqueue the fiber at the current tail index, update the tail
        // value, publish it for other threads and break out of the loop.
        val idx = index(tl)
        buffer(idx) = fiber

        if (isStackTracing) {
          totalFiberCount += 1
        }

        val newTl = unsignedShortAddition(tl, 1)
        Tail.updater.lazySet(this, newTl)
        tail = newTl
        return
      }

      // Preparation for outcome 2, calculating the "real" value of the head.
      val real = lsb(hd)
      if (steal != real) {
        // Outcome 2, there is a concurrent stealer and there is no available
        // capacity for the new fiber. Proceed to enqueue the fiber on the
        // external queue and break out of the loop.
        if (isStackTracing) {
          totalSpilloverCount += 1
          Tail.updater.lazySet(this, tl)
        }

        external.offer(fiber, random)
        return
      }

      // Preparation for outcome 3.
      // There is no concurrent stealer, but there is also no leftover capacity
      // in the buffer, to accept the incoming fiber. Time to transfer half of
      // the queue into the batched queue. This is necessary because the next
      // fibers to be executed may spawn new fibers, which can quickly be
      // enqueued to the local queue, instead of always being delegated to the
      // external queue one by one.
      val realPlusHalf = unsignedShortAddition(real, HalfLocalQueueCapacity)
      val newHd = pack(realPlusHalf, realPlusHalf)
      if (Head.updater.compareAndSet(this, hd, newHd)) {
        // Outcome 3, half of the queue has been claimed by the owner
        // `WorkerThread`, to be transferred to the batched queue.
        // Due to the new tuning, half of the local queue does not equal a
        // single batch anymore, so several batches are created.
        val batches = new Array[Array[Runnable]](BatchesInHalfQueueCapacity)
        var b = 0
        var offset = 0

        // Each batch is populated with fibers from the local queue. References
        // in the buffer are nulled out for garbage collection purposes.
        while (b < BatchesInHalfQueueCapacity) {
          val batch = new Array[Runnable](SpilloverBatchSize)
          var i = 0
          while (i < SpilloverBatchSize) {
            val idx = index(real + offset)
            val f = buffer(idx)
            buffer(idx) = null
            batch(i) = f
            i += 1
            offset += 1
          }

          if (isStackTracing) {
            totalSpilloverCount += SpilloverBatchSize
          }

          batches(b) = batch
          b += 1
        }

        // Enqueue all of the batches of fibers on the batched queue with a bulk
        // add operation.
        external.offerAll(batches, random)
        // Loop again for a chance to insert the original fiber to be enqueued
        // on the local queue.
      }

      // None of the three final outcomes have been reached, loop again for a
      // fresh chance to enqueue the incoming fiber to the local queue, most
      // likely another thread has freed some capacity in the buffer by stealing
      // from the queue.
    }
  }

  /**
   * Enqueues a batch of fibers to the local queue as a single operation without the enqueue
   * overhead for each fiber. It a fiber from the batch to be directly executed without first
   * enqueueing it on the local queue.
   *
   * @note
   *   Can '''only''' be correctly called by the owner [[WorkerThread]] when this queue is
   *   '''empty'''.
   *
   * @note
   *   By convention, each batch of fibers contains exactly
   *   `LocalQueueConstants.OverflowBatchSize` number of fibers.
   *
   * @note
   *   The references inside the batch are not nulled out. It is important to never reference
   *   the batch after this usage, so that it can be garbage collected, and ultimately, the
   *   referenced fibers.
   *
   * @note
   *   In an ideal world, this method would involve only a single publishing of the `tail` of
   *   the queue to carry out the enqueueing of the whole batch of fibers. However, there must
   *   exist some synchronization with other threads, especially in situations where there are
   *   many more worker threads compared to the number of processors. In particular, there is
   *   one very unlucky interleaving where another worker thread can begin a steal operation
   *   from this queue, while this queue is filled to capacity. In that situation, the other
   *   worker thread would reserve half of the fibers in this queue, to transfer them to its own
   *   local queue. While that stealing operation is in place, this queue effectively operates
   *   with half of its capacity for the purposes of enqueueing new fibers. Should the stealing
   *   thread be preempted while the stealing operation is still underway, and the worker thread
   *   which owns this local queue executes '''every''' other fiber and tries enqueueing a
   *   batch, doing so without synchronization can end up overwriting the stolen fibers, simply
   *   because the size of the batch is larger than half of the queue. However, in normal
   *   operation with a properly sized thread pool, this pathological interleaving should never
   *   occur, and is also the reason why this operation has the same performance impact as the
   *   ideal non-synchronized version of this method.
   *
   * @param batch
   *   the batch of fibers to be enqueued on this local queue
   * @param worker
   *   a reference to the owner worker thread, used for setting the active fiber reference
   * @return
   *   a fiber to be executed directly
   */
  def enqueueBatch(batch: Array[Runnable], worker: WorkerThread[_]): Runnable = {
    // A plain, unsynchronized load of the tail of the local queue.
    val tl = tail

    while (true) {
      // A load of the head of the queue using `acquire` semantics.
      val hd = Head.updater.get(this)
      val steal = msb(hd)

      // Check the current occupancy of the queue. In the one pathological case
      // described in the scaladoc for this class, this number will be equal to
      // `LocalQueueCapacity`.
      val len = unsignedShortSubtraction(tl, steal)
      if (len <= LocalQueueCapacityMinusBatch) {
        // It is safe to transfer the fibers from the batch to the queue.
        val startPos = tl - 1
        var i = 1
        while (i < SpilloverBatchSize) {
          val idx = index(startPos + i)
          buffer(idx) = batch(i)
          i += 1
        }

        val fiber = batch(0)

        if (isStackTracing) {
          totalFiberCount += SpilloverBatchSize
          worker.active = fiber
        }

        // Publish the new tail.
        val newTl = unsignedShortAddition(tl, SpilloverBatchSize - 1)
        Tail.updater.lazySet(this, newTl)
        tail = newTl
        // Return a fiber to be directly executed, withouth enqueueing it first
        // on the local queue. This does sacrifice some fairness, because the
        // returned fiber might not be at the head of the local queue, but it is
        // nevertheless an optimization to reduce contention on the local queue
        // should other threads be looking to steal from it, as the returned
        // fiber is already obtained using relatively expensive volatile store
        // operations.
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
   * Dequeues a fiber from the head of the local queue.
   *
   * @note
   *   Can '''only''' be correctly called by the owner [[WorkerThread]].
   *
   * The method returns only when it has successfully "stolen" a fiber from the head of the
   * queue (even in the presence of a concurrent stealer), or the queue is empty, in which case
   * `null` is returned.
   *
   * Dequeueing even in the presence of a concurrent stealer works because stealing is defined
   * as movement of the "real" value of the head of the queue, even before any fibers are taken.
   * Dequeueing only operates on this value. Special care needs to be take to not overwrite the
   * "steal" tag in the presence of a concurrent stealer.
   *
   * @param worker
   *   a reference to the owner worker thread, used for setting the active fiber reference
   * @return
   *   the fiber at the head of the queue, or `null` if the queue is empty (in order to avoid
   *   unnecessary allocations)
   */
  def dequeue(worker: WorkerThread[_]): Runnable = {
    // A plain, unsynchronized load of the tail of the local queue.
    val tl = tail

    // A CAS loop on the head of the queue (since it is a FIFO queue). The loop
    // can break out of the whole method only when it has successfully moved
    // the head by 1 position, securing the fiber to return in the process.
    while (true) {
      // A load of the head of the queue using `acquire` semantics.
      val hd = Head.updater.get(this)

      // Dequeueing only cares about the "real" value of the head.
      val real = lsb(hd)

      if (real == tl) {
        // The queue is empty, there is nothing to return.
        return null
      }

      // Calculate the new "real" value of the head (move it forward by 1).
      val newReal = unsignedShortAddition(real, 1)

      // Make sure to preserve the "steal" tag in the presence of a concurrent
      // stealer. Otherwise, move the "steal" tag along with the "real" value.
      val steal = msb(hd)
      val newHd = if (steal == real) pack(newReal, newReal) else pack(steal, newReal)

      val idx = index(real)
      val fiber = buffer(idx)

      if (isStackTracing) {
        worker.active = fiber
      }

      if (Head.updater.compareAndSet(this, hd, newHd)) {
        // The head has been successfully moved forward and the fiber secured.
        // Proceed to null out the reference to the fiber and return it.
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
   * Steals half of the enqueued fibers from this queue and places them into the destination
   * [[LocalQueue]].
   *
   * @note
   *   Can '''only''' be correctly called by a concurrent [[WorkerThread]] which owns `dst`.
   *
   * Stealing is defined as calculating the half of the available fibers in the local queue and
   * moving the "real" value of the head, while keeping the "steal" tag where it is. This
   * signals to other threads that the executing thread has obtained exclusive access to the
   * local queue and is in the process of transferring fibers. All fibers between the "steal"
   * and "real" values are transferred, and finally, the "steal" tag is updated to match the
   * "real" value of the head. To completely announce that the stealing operation is done, the
   * tail of the destination queue is published. This prevents other threads from stealing from
   * `dst` in the meantime.
   *
   * This contract allows the other methods in this class to observe whenever a stealing
   * operation is ongoing and be extra careful not to overwrite the current state maintained by
   * the stealing [[WorkerThread]].
   *
   * @param dst
   *   the destination local queue where the stole fibers will end up
   * @param dstWorker
   *   a reference to the owner worker thread, used for setting the active fiber reference
   * @return
   *   a reference to the first fiber to be executed by the stealing [[WorkerThread]], or `null`
   *   if the stealing was unsuccessful
   */
  def stealInto(dst: LocalQueue, dstWorker: WorkerThread[_]): Runnable = {
    // A plain, unsynchronized load of the tail of the destination queue, owned
    // by the executing thread.
    val dstTl = dst.tail

    // A load of the head of the destination queue using `acquire` semantics.
    val dstHd = Head.updater.get(dst)

    // Before a steal is attempted, make sure that the destination queue is not
    // being stolen from. It can be argued that an attempt to steal fewer fibers
    // can be made here, but it is simpler to give up completely.
    val dstSteal = msb(dstHd)
    if (unsignedShortSubtraction(dstTl, dstSteal) > HalfLocalQueueCapacity) {
      return null
    }

    // A CAS loop on the head of the queue (since it is a FIFO queue). The loop
    // can break out of the whole method only when it has successfully moved
    // the head by `size / 2` positions, securing the fibers to transfer in the
    // process, the local queue is empty, or there is a `WorkerThread` already
    // stealing from this queue.
    while (true) {
      // A load of the head of the local queue using `acquire` semantics.
      var hd = Head.updater.get(this)

      val steal = msb(hd)
      val real = lsb(hd)

      // Check for the presence of a `WorkerThread` which is already stealing
      // from this queue.
      if (steal != real) {
        // There is a `WorkerThread` stealing from this queue. Give up.
        return null
      }

      // A load of the tail of the local queue using `acquire` semantics.  Here,
      // the `WorkerThread` that executes this code is **not** the owner of this
      // local queue, hence the need for an `acquire` load.
      val tl = Tail.updater.get(this)

      // Calculate the current size of the queue (the number of enqueued fibers).
      var n = unsignedShortSubtraction(tl, real)

      // Take half of them (+ 1). This helps in the situation when a
      // `WorkerThread` is completely blocked executing a blocking operation,
      // and all of its remaining fibers should be stolen.
      n = n - n / 2

      if (n == 0) {
        // There are no fibers to steal. Nothing more to do.
        return null
      }

      // Calculate the new "real" value of the head.
      var newReal = unsignedShortAddition(real, n)
      // Keep the old "steal" tag, different from the new "real" value, to
      // signal to other threads that stealing is underway.
      var newHd = pack(steal, newReal)

      if (Head.updater.compareAndSet(this, hd, newHd)) {
        // The head has been successfully moved forward and the stealing process
        // announced. Proceed to transfer all of the fibers between the old
        // "steal" tag and the new "real" value, nulling out the references for
        // garbage collection purposes.
        val dstBuffer = dst.bufferForwarder

        // Obtain a reference to the first fiber. This fiber will not be
        // transferred to the destination local queue and will instead be
        // executed directly.
        val headFiberIdx = index(steal)
        val headFiber = buffer(headFiberIdx)
        buffer(headFiberIdx) = null

        if (isStackTracing) {
          dstWorker.active = headFiber
        }

        // All other fibers need to be transferred to the destination queue.
        val sourcePos = steal + 1
        val end = n - 1
        var i = 0
        while (i < end) {
          val srcIdx = index(sourcePos + i)
          val dstIdx = index(dstTl + i)
          val fiber = buffer(srcIdx)
          buffer(srcIdx) = null
          dstBuffer(dstIdx) = fiber
          i += 1
        }

        if (isStackTracing) {
          successfulStealAttemptCount += 1
          stolenFiberCount += n
        }

        // After transferring the stolen fibers, it is time to announce that the
        // stealing operation is done, by moving the "steal" tag to match the
        // "real" value of the head. Opportunistically try to set it without
        // reading the `head` again.
        hd = newHd
        while (true) {
          newHd = pack(newReal, newReal)

          if (Head.updater.compareAndSet(this, hd, newHd)) {
            // The "steal" tag now matches the "real" head value. Proceed to
            // return a fiber that can immediately be executed by the stealing
            // `WorkerThread`.

            if (n == 1) {
              // Only 1 fiber has been stolen. No need for any memory
              // synchronization operations.

              if (isStackTracing) {
                Tail.updater.lazySet(dst, dstTl)
                dst.tail = dstTl
              }

              return headFiber
            }

            // Calculate the new tail of the destination local queue. The first
            // stolen fiber is not put into the queue at all and is instead
            // executed directly.
            n -= 1
            val newDstTl = unsignedShortAddition(dstTl, n)

            // Publish the new tail of the destination queue. That way the
            // destination queue also becomes eligible for stealing.
            Tail.updater.lazySet(dst, newDstTl)
            dst.tail = newDstTl
            return headFiber
          } else {
            // Failed to opportunistically restore the value of the `head`. Load
            // it again and retry.
            hd = Head.updater.get(this)
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
   * Steals a batch of enqueued fibers and transfers the whole batch to the batched queue.
   *
   * This method is called by the runtime to restore fairness guarantees between fibers in the
   * local queue compared to fibers on the external and batched queues. Every few iterations,
   * the external and batched queues are checked for fibers and those fibers are executed. In
   * the case of the batched queue, a batch of fibers might be obtained, which cannot fully fit
   * into the local queue due to insufficient capacity. In that case, this method is called to
   * drain one full batch of fibers, which in turn creates space for the fibers arriving from
   * the batched queue.
   *
   * Conceptually, this method is identical to [[LocalQueue#dequeue]], with the main difference
   * being that the `head` of the queue is moved forward by as many places as there are in a
   * batch, thus securing all those fibers and transferring them to the batched queue.
   *
   * @note
   *   Can '''only''' be correctly called by the owner [[WorkerThread]].
   *
   * @param external
   *   the external queue to transfer a batch of fibers into
   * @param random
   *   a reference to an uncontended source of randomness, to be passed along to the striped
   *   concurrent queues when executing their enqueue operations
   */
  def drainBatch(external: ScalQueue[AnyRef], random: ThreadLocalRandom): Unit = {
    // A plain, unsynchronized load of the tail of the local queue.
    val tl = tail

    while (true) {
      // A load of the head of the queue using `acquire` semantics.
      val hd = Head.updater.get(this)

      val real = lsb(hd)

      if (unsignedShortSubtraction(tl, real) <= LocalQueueCapacityMinusBatch) {
        // The current remaining capacity of the local queue is enough to
        // accommodate the new incoming batch. There is nothing more to be done.
        return
      }

      // Move the "real" value of the head by the size of a batch.
      val newReal = unsignedShortAddition(real, SpilloverBatchSize)

      // Make sure to preserve the "steal" tag in the presence of a concurrent
      // stealer. Otherwise, move the "steal" tag along with the "real" value.
      val steal = msb(hd)
      val newHd = if (steal == real) pack(newReal, newReal) else pack(steal, newReal)

      if (Head.updater.compareAndSet(this, hd, newHd)) {
        // The head has been successfully moved forward and a batch of fibers
        // secured. Proceed to null out the references to the fibers and
        // transfer them to the batch.
        val batch = new Array[Runnable](SpilloverBatchSize)
        var i = 0

        while (i < SpilloverBatchSize) {
          val idx = index(real + i)
          val f = buffer(idx)
          buffer(idx) = null
          batch(i) = f
          i += 1
        }

        // The fibers have been transferred, enqueue the whole batch on the
        // batched queue.
        if (isStackTracing) {
          totalSpilloverCount += SpilloverBatchSize
          Tail.updater.lazySet(this, tl)
        }

        external.offer(batch, random)
        return
      }
    }
  }

  /**
   * Checks whether the local queue is empty.
   *
   * @return
   *   `true` if the queue is empty, `false` otherwise
   */
  def isEmpty(): Boolean = {
    val hd = Head.updater.get(this)
    val tl = Tail.updater.get(this)
    lsb(hd) == tl
  }

  /**
   * Checks whether the local queue contains some fibers.
   *
   * @return
   *   `true` if the queue is '''not''' empty, `false` otherwise
   */
  def nonEmpty(): Boolean = !isEmpty()

  /**
   * Returns the number of fibers currently enqueued on this [[LocalQueue]].
   *
   * @note
   *   This number is an approximation and may not be correct while stealing is taking place.
   *
   * @return
   *   the number of fibers currently enqueued on this local queue
   */
  def size(): Int = {
    val hd = Head.updater.get(this)
    val tl = Tail.updater.get(this)
    unsignedShortSubtraction(tl, lsb(hd))
  }

  /**
   * Forwarder method for accessing the backing buffer of another [[LocalQueue]].
   *
   * @return
   *   a reference to [[LocalQueue#buf]]
   */
  def bufferForwarder: Array[Runnable] = buffer

  /**
   * Computes the index into the circular buffer for a given integer value.
   *
   * @param n
   *   the integer value
   * @return
   *   the index into the circular buffer
   */
  private[this] def index(n: Int): Int = n & LocalQueueCapacityMask

  /**
   * Extracts the 16 least significant bits from a 32 bit integer value.
   *
   * @param n
   *   the integer value
   * @return
   *   the 16 least significant bits as an integer value
   */
  private[this] def lsb(n: Int): Int = n & UnsignedShortMask

  /**
   * Extracts the 16 most significant bits from a 32 bit integer value.
   *
   * @param n
   *   the integer value
   * @return
   *   the 16 most significant bits as an integer value
   */
  private[this] def msb(n: Int): Int = n >>> 16

  /**
   * Concatenates two integer values which represent the most significant and least significant
   * 16 bits respectively, into a single 32 bit integer value.
   *
   * @param msb
   *   an integer value that represents the 16 most significant bits
   * @param lsb
   *   an integer value that represents the 16 least significant bits
   * @return
   *   a 32 bit integer value which is a concatenation of the input values
   */
  private[this] def pack(msb: Int, lsb: Int): Int = (msb << 16) | lsb

  /**
   * Encodes addition of unsigned 16 bit values as an operation on 32 bit integers. After
   * performing the addition, only the 16 least significant bits are returned.
   *
   * @param x
   *   the augend
   * @param y
   *   the addend
   * @return
   *   the unsigned 16 bit sum as a 32 bit integer value
   */
  private[this] def unsignedShortAddition(x: Int, y: Int): Int = lsb(x + y)

  /**
   * Encodes subtraction of unsigned 16 bit values as an operation on 32 bit integers. After
   * performing the subtraction, only the 16 least significant bits are returned.
   *
   * @param x
   *   the minuend
   * @param y
   *   the subtrahend
   * @return
   *   the unsigned 16 bit difference as a 32 bit integer value
   */
  private[this] def unsignedShortSubtraction(x: Int, y: Int): Int = lsb(x - y)

  /**
   * Returns a snapshot of the fibers currently enqueued on this local queue.
   *
   * @return
   *   a set of the currently enqueued fibers
   */
  def snapshot(): Set[Runnable] = {
    // load fence to get a more recent snapshot of the enqueued fibers
    val _ = size()
    buffer.toSet - null
  }

  /*
   * What follows is a collection of methods used in the implementation of the
   * `cats.effect.unsafe.metrics.LocalQueueSamplerMBean` interface.
   */

  /**
   * Returns the number of fibers enqueued on this [[LocalQueue]].
   *
   * @return
   *   the number of fibers enqueued on this local queue
   */
  def getFiberCount(): Int = size()

  /**
   * Returns the index into the circular buffer backing this [[LocalQueue]] which represents the
   * head of the queue.
   *
   * @return
   *   the index representing the head of the queue
   */
  def getHeadIndex(): Int = {
    val hd = Head.updater.get(this)
    index(lsb(hd))
  }

  /**
   * Returns the index into the circular buffer backing this [[LocalQueue]] which represents the
   * tail of the queue.
   *
   * @return
   *   the index representing the tail of the queue
   */
  def getTailIndex(): Int = {
    val tl = Tail.updater.get(this)
    index(tl)
  }

  /**
   * Returns the total number of fibers enqueued on this [[LocalQueue]] during its lifetime.
   *
   * @return
   *   the total number of fibers enqueued during the lifetime of this local queue
   */
  def getTotalFiberCount(): Long = {
    val _ = Tail.updater.get(this)
    totalFiberCount
  }

  /**
   * Returns the total number of fibers spilt over to the external queue during the lifetime of
   * this [[LocalQueue]].
   *
   * @return
   *   the total number of fibers spilt over to the external queue
   */
  def getTotalSpilloverCount(): Long = {
    val _ = Tail.updater.get(this)
    totalSpilloverCount
  }

  /**
   * Returns the total number of successful steal attempts by other worker threads from this
   * [[LocalQueue]] during its lifetime.
   *
   * @return
   *   the total number of successful steal attempts by other worker threads
   */
  def getSuccessfulStealAttemptCount(): Long = {
    val _ = Head.updater.get(this)
    successfulStealAttemptCount
  }

  /**
   * Returns the total number of stolen fibers by other worker threads from this [[LocalQueue]]
   * during its lifetime.
   *
   * @return
   *   the total number of stolen fibers by other worker threads
   */
  def getStolenFiberCount(): Long = {
    val _ = Head.updater.get(this)
    stolenFiberCount
  }

  /**
   * Exposes the "real" value of the head of this [[LocalQueue]]. This value represents the
   * state of the head which is valid for the owner worker thread. This is an unsigned 16 bit
   * integer.
   *
   * @note
   *   For more details, consult the comments in the source code for
   *   [[cats.effect.unsafe.LocalQueue]].
   *
   * @return
   *   the "real" value of the head of the local queue
   */
  def getRealHeadTag(): Int = {
    val hd = Head.updater.get(this)
    lsb(hd)
  }

  /**
   * Exposes the "steal" tag of the head of this [[LocalQueue]]. This value represents the state
   * of the head which is valid for any worker thread looking to steal work from this local
   * queue. This is an unsigned 16 bit integer.
   *
   * @note
   *   For more details, consult the comments in the source code for
   *   [[cats.effect.unsafe.LocalQueue]].
   *
   * @return
   *   the "steal" tag of the head of the local queue
   */
  def getStealHeadTag(): Int = {
    val hd = Head.updater.get(this)
    msb(hd)
  }

  /**
   * Exposes the "tail" tag of the tail of this [[LocalQueue]]. This value represents the state
   * of the tail which is valid for the owner worker thread, used for enqueuing fibers into the
   * local queue, as well as any other worker thread looking to steal work from this local
   * queue, used for calculating how many fibers to steal. This is an unsigned 16 bit integer.
   *
   * @note
   *   For more details, consult the comments in the source code for
   *   [[cats.effect.unsafe.LocalQueue]].
   *
   * @return
   *   the "tail" tag of the tail of the local queue
   */
  def getTailTag(): Int = {
    Tail.updater.get(this)
  }
}
