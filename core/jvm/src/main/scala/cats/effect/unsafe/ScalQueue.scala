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

package cats.effect.unsafe

import java.util.concurrent.{ConcurrentLinkedQueue, ThreadLocalRandom}

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
private[effect] final class ScalQueue[A <: AnyRef](threadCount: Int) {

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

  /**
   * The concurrent queues backing this Scal queue.
   */
  private[this] val queues: Array[ConcurrentLinkedQueue[A]] = {
    val nq = numQueues
    val queues = new Array[ConcurrentLinkedQueue[A]](nq)
    var i = 0
    while (i < nq) {
      queues(i) = new ConcurrentLinkedQueue()
      i += 1
    }
    queues
  }

  /**
   * Enqueues a single element on the Scal queue.
   *
   * @param a
   *   the element to be enqueued
   * @param random
   *   an uncontended source of randomness, used for randomly choosing a destination queue
   */
  def offer(a: A, random: ThreadLocalRandom): Unit = {
    val idx = random.nextInt(numQueues)
    queues(idx).offer(a)
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
  def offerAll(as: Array[_ <: A], random: ThreadLocalRandom): Unit = {
    val nq = numQueues
    val len = as.length
    var i = 0
    while (i < len) {
      val fiber = as(i)
      val idx = random.nextInt(nq)
      queues(idx).offer(fiber)
      i += 1
    }
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
  def poll(random: ThreadLocalRandom): A = {
    val nq = numQueues
    val from = random.nextInt(nq)
    var i = 0
    var a = null.asInstanceOf[A]

    while ((a eq null) && i < nq) {
      val idx = (from + i) & mask
      a = queues(idx).poll()
      i += 1
    }

    a
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
  def remove(a: A): Unit = {
    val nq = numQueues
    var i = 0
    var done = false

    while (!done && i < nq) {
      done = queues(i).remove(a)
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
    val nq = numQueues
    var i = 0
    while (i < nq) {
      queues(i).clear()
      i += 1
    }
  }
}
