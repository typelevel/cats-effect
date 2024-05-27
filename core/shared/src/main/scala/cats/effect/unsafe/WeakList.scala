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

import cats.effect.unsafe.ref.{ReferenceQueue, WeakReference}

import scala.annotation.tailrec

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import WeakList.Node

private final class WeakList[A] extends AtomicReference[Node[A]] {
  head =>

  private[this] val queue = new ReferenceQueue[A]()
  private[this] val allowedToPack = new AtomicBoolean(true)
  private[this] var gcCount = 0

  /**
   * Prepends a value to the list
   */
  def prepend(a: A): Unit = {
    packIfNeeded()

    val newHead = new Node(a, queue)

    @tailrec
    def loop(): Unit = {
      val currentHead = head.get()
      newHead.setNext(currentHead)

      if (!head.compareAndSet(currentHead, newHead))
        loop()
    }

    loop()
  }

  def foreach(f: A => Unit): Unit = {
    var currentNode = head.get()
    while (currentNode ne null) {
      val a = currentNode.get()
      if (a != null) f(a)
      currentNode = currentNode.getNext()
    }
  }

  private[this] def packIfNeeded(): Unit =
    if (allowedToPack.compareAndSet(true, false)) {
      try {
        var gcCount = this.gcCount // local copy

        var shouldPack = false
        while (queue.poll() != null) {
          gcCount += 1
          if ((gcCount > 0) && (gcCount & (gcCount - 1)) == 0) { // positive power of 2
            shouldPack = true
            // don't break the loop, keep draining queue
          }
        }

        if (shouldPack) {
          // b/c pack is aggressive, it may clean nodes before we poll them out of the queue
          // in that case, gcCount may go negative
          gcCount -= pack(gcCount)
        }

        this.gcCount = gcCount
      } finally {
        allowedToPack.set(true)
      }
    }

  private[this] def pack(bound: Int): Int = {
    val got = head.get()
    if (got ne null)
      got.packHead(bound, 0, this)
    else
      0
  }

  override def toString(): String = s"WeakList(${get()})"

}

private object WeakList {

  private[WeakList] final class Node[A](a: A, queue: ReferenceQueue[A])
      extends WeakReference(a, queue) {
    private[this] var _next: Node[A] = _ // `next` clashes with field in superclass (?)

    def getNext(): Node[A] = _next

    def setNext(next: Node[A]): Unit = {
      this._next = next
    }

    /**
     * Packs this head node
     */
    @tailrec
    def packHead(bound: Int, removed: Int, root: WeakList[A]): Int = {
      val next = this._next // local copy

      if (get() == null) {
        if (root.compareAndSet(this, next)) {
          if (next == null) {
            // bottomed out
            removed + 1
          } else {
            // note this can cause the bound to go negative, which is fine
            next.packHead(bound - 1, removed + 1, root)
          }
        } else {
          val prev = root.get()
          if ((prev != null) && (prev.getNext() eq this)) {
            // prev is our new parent, we are its tail
            this.packTail(bound, removed, prev)
          } else if (next != null) { // we were unable to remove ourselves, but we can still pack our tail
            next.packTail(bound - 1, removed, this)
          } else {
            removed
          }
        }
      } else {
        if (next == null) {
          // bottomed out
          removed
        } else {
          if (bound > 0)
            next.packTail(bound - 1, removed, this)
          else
            removed
        }
      }
    }

    /**
     * Packs this non-head node
     */
    @tailrec
    private def packTail(bound: Int, removed: Int, prev: Node[A]): Int = {
      val next = this._next // local copy

      if (get() == null) {
        // We own the pack lock, so it is safe to write `next`. It will be published to subsequent packs via the lock.
        // Concurrent readers ie `WeakList#foreach` may read a stale value for `next` still pointing to this node.
        // This is okay b/c the new `next` (this node's tail) is still reachable via the old `next` (this node).
        prev.setNext(next)
        if (next == null) {
          // bottomed out
          removed + 1
        } else {
          // note this can cause the bound to go negative, which is fine
          next.packTail(bound - 1, removed + 1, prev)
        }
      } else {
        if (next == null) {
          // bottomed out
          removed
        } else {
          if (bound > 0)
            next.packTail(bound - 1, removed, this)
          else
            removed
        }
      }
    }

    override def toString(): String = s"Node(${get()}, ${_next})"
  }

}
