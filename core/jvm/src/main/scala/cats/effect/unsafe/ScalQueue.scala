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

import java.util.concurrent.{ConcurrentLinkedQueue, ThreadLocalRandom}
import java.util.concurrent.atomic.AtomicLong

/**
 * A striped queue implementation inspired by the [[https://scal.cs.uni-salzburg.at/dq/ Scal]]
 * project. The whole queue consists of several [[java.util.concurrent.ConcurrentLinkedQueue]]
 * instances (the number of queues is a power of 2 for optimization purposes) which are load
 * balanced between using random index generation.
 *
 * The Scal queue does not guarantee any ordering of dequeued elements and is more akin to the
 * data structure known as '''bag'''. The naming is kept to honor the original development
 * efforts.
 *
 * @param threadCount
 *   the number of threads to load balance between
 */
private[effect] final class ScalQueue(threadCount: Int) {

  /**
   * Calculates the next power of 2 using bitwise operations. This value actually represents the
   * bitmask for the next power of 2 and can be used for indexing into the array of concurrent
   * queues.
   */
  private[this] val mask: Int = {
    // Bit twiddling hacks.
    // http://graphics.stanford.edu/~seander/bithacks.html#RoundUpPowerOf2
    var value = threadCount - 1
    value |= value >> 1
    value |= value >> 2
    value |= value >> 4
    value |= value >> 8
    value | value >> 16
  }

  /**
   * The number of queues to load balance between (a power of 2 equal to `threadCount` if
   * `threadCount` is a power of 2, otherwise the next power of 2 larger than `threadCount`).
   */
  private[this] val numQueues: Int = mask + 1

  private def createAtomicLongArray(size: Int): Array[AtomicLong] = {
  val counts = new Array[AtomicLong](size)
  var i = 0
  while (i < size) {
    counts(i) = new AtomicLong(0)
    i += 1
  }
  counts
}

  // Metrics counters for tracking external queue submissions and present counts
private[this] val singletonsSubmittedCounts: Array[AtomicLong] = createAtomicLongArray(numQueues)
private[this] val singletonsPresentCounts: Array[AtomicLong] = createAtomicLongArray(numQueues)
private[this] val batchesSubmittedCounts: Array[AtomicLong] = createAtomicLongArray(numQueues)
private[this] val batchesPresentCounts: Array[AtomicLong] = createAtomicLongArray(numQueues)
private[this] val totalFiberSubmittedCounts: Array[AtomicLong] = createAtomicLongArray(numQueues)
private[this] val fiberPresentCounts: Array[AtomicLong] = createAtomicLongArray(numQueues)

  /**
   * The concurrent queues backing this Scal queue.
   */
  private[this] val queues: Array[ConcurrentLinkedQueue[AnyRef]] = {
    val nq = numQueues
    val queues = new Array[ConcurrentLinkedQueue[AnyRef]](nq)
    var i = 0
    while (i < nq) {
      queues(i) = new ConcurrentLinkedQueue()
      i += 1
    }
    queues
  }

  /**
   * Enqueues a single element (singleton task) on the Scal queue.
   *
   * @param a
   *   the element to be enqueued
   * @param random
   *   an uncontended source of randomness, used for randomly choosing a destination queue
   */
  def offer(a: Runnable, random: ThreadLocalRandom): Unit = {
    val idx = random.nextInt(numQueues)
    queues(idx).offer(a)

    // Track as singleton task - using the same index for striped counters
    singletonsSubmittedCounts(idx).incrementAndGet()
    singletonsPresentCounts(idx).incrementAndGet()

    // Also increment total fiber counts
    totalFiberSubmittedCounts(idx).incrementAndGet()
    fiberPresentCounts(idx).incrementAndGet()
    ()
  }

  /**
   * Enqueues a batch element (Array of tasks) on the Scal queue.
   *
   * @param batch
   *   the batch to be enqueued
   * @param random
   *   an uncontended source of randomness, used for randomly choosing a destination queue
   */

  def offerBatch(batch: Array[Runnable], random: ThreadLocalRandom): Unit = {
    val idx = random.nextInt(numQueues)
    queues(idx).offer(batch)

    // Track as batch task
    batchesSubmittedCounts(idx).incrementAndGet()
    batchesPresentCounts(idx).incrementAndGet()

    // Also increment total fiber counts by batch size
    val batchSize = batch.length
    totalFiberSubmittedCounts(idx).addAndGet(batchSize.toLong)
    fiberPresentCounts(idx).addAndGet(batchSize.toLong)
    ()
  }

  /**
   * Enqueues a batch of elements in a striped fashion.
   *
   * @note
   *   By convention, the array of elements cannot contain any null references, which are
   *   unsupported by the underlying concurrent queues.
   *
   * @note
   *   This method has a somewhat high overhead when enqueueing every single element. However,
   *   this is acceptable in practice because this method is only used on the slowest path when
   *   blocking operations are anticipated, which is not what the fiber runtime is optimized
   *   for. This overhead can be substituted for the overhead of allocation of array list
   *   instances which contain some of the fibers of the batch, so that they can be enqueued
   *   with a bulk operation on each of the concurrent queues. Maybe this can be explored in the
   *   future, but remains to be seen if it is a worthwhile tradeoff.
   *
   * @param as
   *   the batch of elements to be enqueued
   * @param random
   *   an uncontended source of randomness, used for randomly choosing a destination queue
   */
  def offerAll(as: Array[Runnable], random: ThreadLocalRandom): Unit = {
    val nq = numQueues
    val len = as.length
    var i = 0
    while (i < len) {
      val fiber = as(i)
      val idx = random.nextInt(nq)
      queues(idx).offer(fiber)

      // Track as singleton task
      singletonsSubmittedCounts(idx).incrementAndGet()
      singletonsPresentCounts(idx).incrementAndGet()

      // Also increment total fiber counts
      totalFiberSubmittedCounts(idx).incrementAndGet()
      fiberPresentCounts(idx).incrementAndGet()

      i += 1
    }
    ()
  }

  /**
   * Offers multiple batches of tasks to this Scal queue.
   *
   * @param batches
   *   an array of runnable batches to be offered to the queue
   * @param random
   *   a reference to an uncontended source of randomness, to be passed along to the striped
   *   concurrent queues when executing their offer operations
   */
  def offerAllBatches(batches: Array[Array[Runnable]], random: ThreadLocalRandom): Unit = {
    val nq = numQueues
    val len = batches.length
    var i = 0
    while (i < len) {
      val batch = batches(i)
      val idx = random.nextInt(nq)
      queues(idx).offer(batch)

      // Track as batch task
      batchesSubmittedCounts(idx).incrementAndGet()
      batchesPresentCounts(idx).incrementAndGet()

      // Also increment total fiber counts by batch size
      val batchSize = batch.length
      totalFiberSubmittedCounts(idx).addAndGet(batchSize.toLong)
      fiberPresentCounts(idx).addAndGet(batchSize.toLong)

      i += 1
    }
    ()
  }

  /**
   * Dequeues an element from this Scal queue.
   *
   * @param random
   *   an uncontended source of randomness, used for randomly selecting the first queue to look
   *   for elements
   * @return
   *   an element from this Scal queue or `null` if this queue is empty
   */
  // Update the poll method (around line 230)

  def poll(random: ThreadLocalRandom): AnyRef = {
    val nq = numQueues
    val from = random.nextInt(nq)
    var i = 0
    var element: AnyRef = null

    while ((element eq null) && i < nq) {
      val idx = (from + i) & mask
      element = queues(idx).poll()

      // If we found an element, decrement the appropriate counter
      if (element ne null) {
        if (element.isInstanceOf[Array[Runnable]]) {
          batchesPresentCounts(idx).decrementAndGet()
          // Decrement fiber present count by batch size
          val batchSize = element.asInstanceOf[Array[Runnable]].length
          fiberPresentCounts(idx).addAndGet(-batchSize.toLong);
          ()
        } else {
          singletonsPresentCounts(idx).decrementAndGet();
          // Decrement fiber present count by 1
          fiberPresentCounts(idx).decrementAndGet();
          ()
        }
      }

      i += 1
    }

    element
  }

  /**
   * Removes an element from this queue.
   *
   * @note
   *   The implementation delegates to the
   *   [[java.util.concurrent.ConcurrentLinkedQueue#remove remove]] method.
   *
   * @note
   *   This method runs in linear time relative to the size of the queue, which is not ideal and
   *   generally should not be used. However, this functionality is necessary for the blocking
   *   mechanism of the [[WorkStealingThreadPool]]. The runtime complexity of this method is
   *   acceptable for that purpose because threads are limited resources.
   *
   * @param a
   *   the element to be removed
   */
  def remove(a: AnyRef): Unit = {
    val nq = numQueues
    var i = 0
    var done = false

    while (!done && i < nq) {
      done = queues(i).remove(a)

      // If we removed the element, decrement the appropriate counter
      if (done) {
        if (a.isInstanceOf[Array[Runnable]]) {
          batchesPresentCounts(i).decrementAndGet()
          // Decrement fiber present count by batch size
          val batchSize = a.asInstanceOf[Array[Runnable]].length
          fiberPresentCounts(i).addAndGet(-batchSize.toLong);
          ()
        } else {
          singletonsPresentCounts(i).decrementAndGet();
          // Decrement fiber present count by 1
          fiberPresentCounts(i).decrementAndGet(); ()
        }
      }

      i += 1
    }
  }

  /**
   * Returns a snapshot of the elements currently enqueued on this local queue.
   *
   * @return
   *   a set of the currently enqueued elements
   */
  def snapshot(): Set[AnyRef] =
    queues.flatMap(_.toArray).toSet

  /**
   * Checks if this Scal queue is empty.
   *
   * The Scal queue is defined as empty if '''all''' concurrent queues report that they contain
   * no elements.
   *
   * @return
   *   `true` if this Scal queue is empty, `false` otherwise
   */
  def isEmpty(): Boolean = {
    val nq = numQueues
    var i = 0
    var empty = true

    while (empty && i < nq) {
      empty = queues(i).isEmpty()
      i += 1
    }

    empty
  }

  /**
   * Checks if this Scal queue is '''not''' empty.
   *
   * The Scal queue is defined as not empty if '''any''' concurrent queue reports that it
   * contains some elements.
   *
   * @return
   *   `true` if this Scal queue is '''not''' empty, `false` otherwise
   */
  def nonEmpty(): Boolean =
    !isEmpty()

  /**
   * Clears all concurrent queues that make up this Scal queue.
   */
  def clear(): Unit = {
    var i = 0
    while (i < numQueues) {
      queues(i).clear()

      // Reset all counters for this stripe
      singletonsPresentCounts(i).set(0)
      batchesPresentCounts(i).set(0)
      fiberPresentCounts(i).set(0)

      i += 1
    }
  }

  /**
   * Returns the total number of singleton tasks submitted to this queue.
   */
  def getTotalSingletonCount(): Long = {
    var total = 0L
    var i = 0
    while (i < numQueues) {
      total += totalSingletonCounts(i).get()
      i += 1
    }
    total
  }

  /**
   * Returns the number of singleton tasks currently in this queue.
   */
  def getSingletonCount(): Long = {
    var total = 0L
    var i = 0
    while (i < numQueues) {
      total += singletonsPresentCounts(i).get()
      i += 1
    }
    total
  }

  /**
   * Returns the total number of batch tasks submitted to this queue.
   */
  def getTotalBatchCount(): Long = {
    var total = 0L
    var i = 0
    while (i < numQueues) {
      total += batchesSubmittedCounts(i).get()
      i += 1
    }
    total
  }

  /**
   * Returns the number of batch tasks currently in this queue.
   */
  def getBatchCount(): Long = {
    var total = 0L
    var i = 0
    while (i < numQueues) {
      total += batchesPresentCounts(i).get()
      i += 1
    }
    total
  }

  /**
   * Returns the total number of fibers (individual tasks + fibers in batches) submitted to this
   * queue.
   */
  def getTotalFiberCount(): Long = {
    var total = 0L
    var i = 0
    while (i < numQueues) {
      total += totalFiberSubmittedCounts(i).get()
      i += 1
    }
    total
  }

  /**
   * Returns the number of fibers (individual tasks + fibers in batches) currently in this
   * queue.
   */
  def getFiberCount(): Long = {
    var total = 0L
    var i = 0
    while (i < numQueues) {
      total += fiberPresentCounts(i).get()
      i += 1
    }
    total
  }
}
