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
 * Scala.js (https://www.scala-js.org/)
 *
 * Copyright EPFL.
 *
 * Licensed under Apache License 2.0
 * (https://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package cats.effect
package unsafe

import scala.annotation.tailrec

import java.util.Arrays
import java.util.concurrent.atomic.AtomicInteger

/**
 * A specialized heap that serves as a priority queue for timers i.e. callbacks with trigger
 * times.
 *
 * In general, this heap is not threadsafe and modifications (insertion/removal) may only be
 * performed on its owner WorkerThread. The exception is that the callback value of nodes may be
 * `null`ed by other threads and published via data race.
 *
 * Other threads may traverse the heap with the `steal` method during which they may `null` some
 * callbacks. This is entirely subject to data races.
 *
 * The only explicit synchronization is the `canceledCounter` atomic, which is used to track and
 * publish cancelations from other threads. Because other threads cannot safely remove a node,
 * they `null` the callback, toggle the `canceled` flag, and increment the counter to indicate
 * that the owner thread should iterate the heap to remove these nodes a.k.a. "packing".
 *
 * To amortize the cost of packing, we only do so if canceled nodes make up at least half of the
 * heap. In an ideal world, cancelation from external threads is relatively rare and those nodes
 * are removed naturally as they surface to the top of the heap, such that we never exceed the
 * packing threshold.
 */
private final class TimerHeap extends AtomicInteger {
  // At most this many nodes are externally canceled and waiting to be removed from the heap.
  canceledCounter =>

  // And this is how many of those externally canceled nodes were already removed.
  // We track this separately so we can increment on owner thread without overhead of the atomic.
  private[this] var removedCanceledCounter = 0

  // The index 0 is not used; the root is at index 1.
  // This is standard practice in binary heaps, to simplify arithmetics.
  private[this] var heap: Array[Node] = new Array(8) // TODO what initial value
  private[this] var size: Int = 0

  private[this] val RightUnit = Right(())

  /**
   * only called by owner thread
   */
  @tailrec
  def peekFirstTriggerTime(): Long =
    if (size > 0) {
      val root = heap(1)

      if (root.isDeleted()) { // DOA. Remove it and loop.

        removeAt(1)
        if (root.isCanceled())
          removedCanceledCounter += 1

        peekFirstTriggerTime() // loop

      } else { // We got a live one!

        val tt = root.triggerTime
        if (tt != Long.MinValue) { // tt != sentinel
          tt
        } else {
          // in the VERY unlikely case when
          // the trigger time is exactly our
          // sentinel, we just cheat a little
          // (this could cause threads to wake
          // up 1 ns too early):
          Long.MaxValue
        }

      }
    } else { // size == 0
      Long.MinValue // sentinel
    }

  /**
   * for testing
   */
  def peekFirstQuiescent(): Right[Nothing, Unit] => Unit = {
    if (size > 0) heap(1).get()
    else null
  }

  /**
   * only called by owner thread
   */
  def pollFirstIfTriggered(now: Long): Right[Nothing, Unit] => Unit = {
    val heap = this.heap // local copy

    @tailrec
    def loop(): Right[Nothing, Unit] => Unit = if (size > 0) {
      val root = heap(1)
      val rootDeleted = root.isDeleted()
      val rootExpired = !rootDeleted && isExpired(root, now)
      if (rootDeleted || rootExpired) {
        root.index = -1
        if (size > 1) {
          heap(1) = heap(size)
          fixDown(1)
        }
        heap(size) = null
        size -= 1

        if (root.isCanceled())
          removedCanceledCounter += 1

        val back = root.getAndClear()
        if (rootExpired && (back ne null)) back else loop()
      } else null
    } else null

    loop()
  }

  /**
   * called by other threads
   */
  def steal(now: Long): Boolean = {
    def go(heap: Array[Node], size: Int, m: Int): Boolean =
      if (m <= size) {
        val node = heap(m)
        if ((node ne null) && isExpired(node, now)) {
          val cb = node.getAndClear()
          val invoked = cb ne null
          if (invoked) cb(RightUnit)

          val leftInvoked = go(heap, size, 2 * m)
          val rightInvoked = go(heap, size, 2 * m + 1)

          invoked || leftInvoked || rightInvoked
        } else false
      } else false

    val heap = this.heap // local copy
    if (heap ne null) {
      val size = Math.min(this.size, heap.length - 1)
      go(heap, size, 1)
    } else false
  }

  /**
   * only called by owner thread
   */
  def insert(
      now: Long,
      delay: Long,
      callback: Right[Nothing, Unit] => Unit,
      out: Array[Right[Nothing, Unit] => Unit]
  ): Function0[Unit] with Runnable = if (size > 0) {
    val heap = this.heap // local copy
    val triggerTime = computeTriggerTime(now, delay)

    val root = heap(1)
    val rootDeleted = root.isDeleted()
    val rootExpired = !rootDeleted && isExpired(root, now)
    if (rootDeleted || rootExpired) { // see if we can just replace the root
      root.index = -1
      if (root.isCanceled()) removedCanceledCounter += 1
      if (rootExpired) out(0) = root.getAndClear()
      val node = new Node(triggerTime, callback, 1)
      heap(1) = node
      fixDown(1)
      node
    } else { // insert at the end
      val heap = growIfNeeded() // new heap array if it grew
      size += 1
      val node = new Node(triggerTime, callback, size)
      heap(size) = node
      fixUp(size)
      node
    }
  } else {
    val node = new Node(now + delay, callback, 1)
    this.heap(1) = node
    size += 1
    node
  }

  /**
   * only called by owner thread
   */
  @tailrec
  def packIfNeeded(): Unit = {

    val back = canceledCounter.get()

    // Account for canceled nodes that were already removed.
    val canceledCount = back - removedCanceledCounter

    if (canceledCount >= size / 2) { // We have exceeded the packing threshold.

      // We will attempt to remove this many nodes.
      val removeCount = // First try to use our current value but get latest if it is stale.
        if (canceledCounter.compareAndSet(back, 0)) canceledCount
        else canceledCounter.getAndSet(0) - removedCanceledCounter

      removedCanceledCounter = 0 // Reset, these have now been accounted for.

      // All external cancelations are now visible (published via canceledCounter).
      pack(removeCount)

    } else { // canceledCounter will eventually overflow if we do not subtract removedCanceledCounter.

      if (canceledCounter.compareAndSet(back, canceledCount)) {
        removedCanceledCounter = 0 // Reset, these have now been accounted for.
      } else {
        packIfNeeded() // canceledCounter was externally incremented, loop.
      }
    }
  }

  private[this] def pack(removeCount: Int): Unit = {
    val heap = this.heap // local copy

    // We track how many canceled nodes we removed so we can try to exit the loop early.
    var i = 1
    var r = 0
    while (r < removeCount && i <= size) {
      // We are careful to consider only *canceled* nodes, which increment the canceledCounter.
      // A node may be deleted b/c it was stolen, but this does not increment the canceledCounter.
      // To avoid leaks we must attempt to find a canceled node for every increment.
      if (heap(i).isCanceled()) {
        removeAt(i)
        r += 1
        // Don't increment i, the new i may be canceled too.
      } else {
        i += 1
      }
    }
  }

  /**
   * only called by owner thread
   */
  private def removeAt(i: Int): Unit = {
    val heap = this.heap // local copy
    val back = heap(i)
    back.getAndClear()
    back.index = -1
    if (i == size) {
      heap(i) = null
      size -= 1
    } else {
      val last = heap(size)
      heap(size) = null
      heap(i) = last
      last.index = i
      size -= 1
      fixUpOrDown(i)
    }
  }

  private[this] def isExpired(node: Node, now: Long): Boolean =
    cmp(node.triggerTime, now) <= 0 // triggerTime <= now

  private[this] def growIfNeeded(): Array[Node] = {
    val heap = this.heap // local copy
    if (size >= heap.length - 1) {
      val newHeap = Arrays.copyOf(heap, heap.length * 2, classOf[Array[Node]])
      this.heap = newHeap
      newHeap
    } else heap
  }

  /**
   * Fixes the heap property around the child at index `m`, either up the tree or down the tree,
   * depending on which side is found to violate the heap property.
   */
  private[this] def fixUpOrDown(m: Int): Unit = {
    val heap = this.heap // local copy
    if (m > 1 && cmp(heap(m >> 1), heap(m)) > 0)
      fixUp(m)
    else
      fixDown(m)
  }

  /**
   * Fixes the heap property from the last child at index `size` up the tree, towards the root.
   */
  private[this] def fixUp(m: Int): Unit = {
    val heap = this.heap // local copy

    /* At each step, even though `m` changes, the element moves with it, and
     * hence heap(m) is always the same initial `heapAtM`.
     */
    val heapAtM = heap(m)

    @tailrec
    def loop(m: Int): Unit = {
      if (m > 1) {
        val parent = m >> 1
        val heapAtParent = heap(parent)
        if (cmp(heapAtParent, heapAtM) > 0) {
          heap(parent) = heapAtM
          heap(m) = heapAtParent
          heapAtParent.index = m
          loop(parent)
        } else heapAtM.index = m
      } else heapAtM.index = m
    }

    loop(m)
  }

  /**
   * Fixes the heap property from the child at index `m` down the tree, towards the leaves.
   */
  private[this] def fixDown(m: Int): Unit = {
    val heap = this.heap // local copy

    /* At each step, even though `m` changes, the element moves with it, and
     * hence heap(m) is always the same initial `heapAtM`.
     */
    val heapAtM = heap(m)

    @tailrec
    def loop(m: Int): Unit = {
      var j = 2 * m // left child of `m`
      if (j <= size) {
        var heapAtJ = heap(j)

        // if the left child is greater than the right child, switch to the right child
        if (j < size) {
          val heapAtJPlus1 = heap(j + 1)
          if (cmp(heapAtJ, heapAtJPlus1) > 0) {
            j += 1
            heapAtJ = heapAtJPlus1
          }
        }

        // if the node `m` is greater than the selected child, swap and recurse
        if (cmp(heapAtM, heapAtJ) > 0) {
          heap(m) = heapAtJ
          heapAtJ.index = m
          heap(j) = heapAtM
          loop(j)
        } else heapAtM.index = m
      } else heapAtM.index = m
    }

    loop(m)
  }

  /**
   * Compares trigger times.
   *
   * The trigger times are `System.nanoTime` longs, so they have to be compared in a peculiar
   * way (see javadoc there). This makes this order non-transitive, which is quite bad. However,
   * `computeTriggerTime` makes sure that there is no overflow here, so we're okay.
   */
  private[this] def cmp(
      xTriggerTime: Long,
      yTriggerTime: Long
  ): Int = {
    val d = xTriggerTime - yTriggerTime
    java.lang.Long.signum(d)
  }

  private[this] def cmp(x: Node, y: Node): Int =
    cmp(x.triggerTime, y.triggerTime)

  /**
   * Computes the trigger time in an overflow-safe manner. The trigger time is essentially `now
   * + delay`. However, we must constrain all trigger times in the heap to be within
   * `Long.MaxValue` of each other (otherwise there will be overflow when comparing in `cpr`).
   * Thus, if `delay` is so big, we'll reduce it to the greatest allowable (in `overflowFree`).
   *
   * From the public domain JSR-166 `ScheduledThreadPoolExecutor` (`triggerTime` method).
   */
  private[this] def computeTriggerTime(now: Long, delay: Long): Long = {
    val safeDelay = if (delay < (Long.MaxValue >> 1)) delay else overflowFree(now, delay)
    now + safeDelay
  }

  /**
   * See `computeTriggerTime`. The overflow can happen if a callback was already triggered
   * (based on `now`), but was not removed yet; and `delay` is sufficiently big.
   *
   * From the public domain JSR-166 `ScheduledThreadPoolExecutor` (`overflowFree` method).
   *
   * Pre-condition that the heap is non-empty.
   */
  private[this] def overflowFree(now: Long, delay: Long): Long = {
    val root = heap(1)
    val rootDelay = root.triggerTime - now
    if ((rootDelay < 0) && (delay - rootDelay < 0)) {
      // head was already triggered, and `delay` is big enough,
      // so we must clamp `delay`:
      Long.MaxValue + rootDelay
    } else {
      delay
    }
  }

  override def toString() = if (size > 0) "TimerHeap(...)" else "TimerHeap()"

  private final class Node(
      val triggerTime: Long,
      private[this] var callback: Right[Nothing, Unit] => Unit,
      var index: Int
  ) extends Function0[Unit]
      with Runnable {

    private[this] var canceled: Boolean = false

    def getAndClear(): Right[Nothing, Unit] => Unit = {
      val back = callback
      if (back ne null) // only clear if we read something
        callback = null
      back
    }

    def get(): Right[Nothing, Unit] => Unit = callback

    /**
     * Cancel this timer.
     */
    def apply(): Unit = {
      // we can always clear the callback, without explicitly publishing
      callback = null

      // if this node is not removed immediately, this will be published by canceledCounter
      canceled = true

      // if we're on the thread that owns this heap, we can remove ourselves immediately
      val thread = Thread.currentThread()
      if (thread.isInstanceOf[WorkerThread[_]]) {
        val worker = thread.asInstanceOf[WorkerThread[_]]
        val heap = TimerHeap.this
        if (worker.ownsTimers(heap)) {
          // remove only if we are still in the heap
          if (index >= 0) heap.removeAt(index)
        } else { // otherwise this heap will need packing
          // it is okay to increment more than once if invoked multiple times
          // but it will undermine the packIfNeeded short-circuit optimization
          // b/c it will keep looking for more canceled nodes
          canceledCounter.getAndIncrement()
          ()
        }
      } else {
        canceledCounter.getAndIncrement()
        ()
      }
    }

    def run() = apply()

    def isDeleted(): Boolean = callback eq null

    def isCanceled(): Boolean = canceled

    override def toString() = s"Node($triggerTime, $callback})"

  }

}
