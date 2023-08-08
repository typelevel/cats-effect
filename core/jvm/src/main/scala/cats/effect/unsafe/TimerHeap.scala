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

import java.util.concurrent.ThreadLocalRandom

private final class TimerHeap {

  // The index 0 is not used; the root is at index 1.
  // This is standard practice in binary heaps, to simplify arithmetics.
  private[this] var heap: Array[TimerNode] = new Array(8) // TODO what initial value
  private[this] var size: Int = 0

  private[this] val RightUnit = IOFiber.RightUnit

  def peekFirstTriggerTime(): Long = {
    val heap = this.heap // local copy
    if (size > 0) {
      val node = heap(1)
      if (node ne null)
        node.triggerTime
      else java.lang.Long.MIN_VALUE
    } else java.lang.Long.MIN_VALUE
  }

  /**
   * only called by owner thread
   *
   * returns `true` if we removed at least one timer TODO only return true if we actually
   * triggered a timer, not just removed one
   */
  def trigger(now: Long): Boolean = {
    val heap = this.heap // local copy

    @tailrec
    def loop(triggered: Boolean): Boolean = if (size > 0) {
      val root = heap(1)
      if (canRemove(root, now)) {
        heap(1) = heap(size)
        heap(size) = null
        size -= 1
        fixDown(1)

        loop(true)
      } else triggered
    } else triggered

    loop(false)
  }

  /**
   * called by other threads
   */
  def steal(now: Long): Boolean = {
    def go(heap: Array[TimerNode], size: Int, m: Int): Boolean =
      if (m <= size) {
        val node = heap(m)
        if ((node ne null) && triggered(node, now)) {
          go(heap, size, 2 * m)
          go(heap, size, 2 * m + 1)
          true
        } else false
      } else false

    val heap = this.heap // local copy
    val size = Math.min(this.size, heap.length - 1)
    go(heap, size, 1)
  }

  /**
   * only called by owner thread
   */
  def insert(
      now: Long,
      delay: Long,
      callback: Right[Nothing, Unit] => Unit,
      tlr: ThreadLocalRandom
  ): Function0[Unit] with Runnable = if (size > 0) {
    val heap = this.heap // local copy
    val node = new TimerNode(now + delay, callback)

    if (canRemove(heap(1), now)) { // see if we can just replace the root
      heap(1) = node
      fixDown(1)
    } else { // look for a canceled node we can replace

      @tailrec
      def loop(m: Int, entropy: Int): Unit = if (m <= size) {
        if (heap(m).isCanceled()) { // we found a spot!
          heap(m) = node
          fixUpOrDown(m)
        } else loop(2 * m + (entropy % 2), entropy >>> 1)
      } else { // insert at the end
        val heap = growIfNeeded() // new heap array if it grew
        size += 1
        heap(size) = node
        fixUp(size)
      }

      val entropy = tlr.nextInt()
      loop(2 + (entropy % 2), entropy >>> 1)
    }

    node
  } else {
    val node = new TimerNode(now + delay, callback)
    this.heap(1) = node
    size += 1
    node
  }

  /**
   * For testing
   */
  private[unsafe] final def insertTlr(
      now: Long,
      delay: Long,
      callback: Right[Nothing, Unit] => Unit
  ): Runnable = {
    insert(now, delay, callback, ThreadLocalRandom.current())
  }

  /**
   * Determines if a node can be removed. Triggers it if relevant.
   */
  private[this] def canRemove(node: TimerNode, now: Long): Boolean =
    node.isCanceled() || triggered(node, now)

  private[this] def triggered(node: TimerNode, now: Long): Boolean =
    if (cmp(node.triggerTime, now) <= 0) { // triggerTime <= now
      node.trigger(RightUnit)
      true
    } else false

  private[this] def growIfNeeded(): Array[TimerNode] = {
    val heap = this.heap // local copy
    if (size >= heap.length - 1) {
      val newHeap = new Array[TimerNode](heap.length * 2)
      System.arraycopy(heap, 1, newHeap, 1, heap.length - 1)
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
   * Along the way we remove up to one canceled node.
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
          loop(parent)
        }
      }
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
          heap(j) = heapAtM
          loop(j)
        }
      }
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

  private[this] def cmp(x: TimerNode, y: TimerNode): Int =
    cmp(x.triggerTime, y.triggerTime)

  override def toString() = heap.drop(1).take(size).mkString("TimerHeap(", ", ", ")")

}

private final class TimerNode(
    val triggerTime: Long,
    private[this] var callback: Right[Nothing, Unit] => Unit)
    extends Function0[Unit]
    with Runnable {

  def trigger(rightUnit: Right[Nothing, Unit]): Unit = {
    val back = callback
    callback = null
    if (back ne null) back(rightUnit)
  }

  /**
   * racy cancelation
   */
  def apply(): Unit = callback = null

  def run() = apply()

  def isCanceled(): Boolean = callback eq null

  override def toString() = s"TimerNode($triggerTime, $callback})"

}
