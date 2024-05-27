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

package cats.effect

import scala.annotation.tailrec

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

import CallbackStack.Handle
import CallbackStack.Node
import Platform.static

private final class CallbackStack[A](private[this] var callback: A => Unit)
    extends AtomicReference[Node[A]] {
  head =>

  private[this] val allowedToPack = new AtomicBoolean(true)

  /**
   * Pushes a callback to the top of the stack. Returns a handle that can be used with
   * [[clearHandle]] to clear the callback.
   */
  def push(cb: A => Unit): Handle[A] = {
    val newHead = new Node(cb)

    @tailrec
    def loop(): Handle[A] = {
      val currentHead = head.get()
      newHead.setNext(currentHead)

      if (!head.compareAndSet(currentHead, newHead))
        loop()
      else
        newHead
    }

    loop()
  }

  def unsafeSetCallback(cb: A => Unit): Unit = {
    callback = cb
  }

  /**
   * Invokes *all* non-null callbacks in the queue, starting with the current one. Returns true
   * iff *any* callbacks were invoked.
   */
  def apply(a: A): Boolean = {
    // see also note about data races in Node#packTail
    var currentNode = head.get()
    try {
      val cb = callback
      var invoked = if (cb != null) {
        cb(a)
        true
      } else {
        false
      }

      // note that we're tearing down the callback stack structure behind us as we go
      while (currentNode ne null) {
        val cb = currentNode.getCallback()
        if (cb != null) {
          cb(a)
          currentNode.clear()
          invoked = true
        }
        val nextNode = currentNode.getNext()
        currentNode.setNext(null)
        currentNode = nextNode
      }
      head.lazySet(null)

      invoked
    } finally {
      // if a callback throws, we stop invoking remaining callbacks
      // but we continue the process of tearing down the stack to prevent memory leaks
      while (currentNode ne null) {
        val nextNode = currentNode.getNext()
        currentNode.clear()
        currentNode.setNext(null)
        currentNode = nextNode
      }
      head.lazySet(null)
    }
  }

  /**
   * Removes the callback referenced by a handle. Returns `true` if the data structure was
   * cleaned up immediately, `false` if a subsequent call to [[pack]] is required.
   */
  def clearHandle(handle: CallbackStack.Handle[A]): Boolean = {
    handle.clear()
    false
  }

  /**
   * It is intended that `bound` be tracked externally and incremented on each clear(). Whenever
   * pack is called, the number of empty cells removed from the stack is produced. It is
   * expected that this value should be subtracted from `bound` for the subsequent pack/clear
   * calls. It is permissible to pack on every clear() for simplicity, though it may be more
   * reasonable to delay pack() calls until bound exceeds some reasonable threshold.
   *
   * The observation here is that it is cheapest to remove empty cells from the front of the
   * list, but very expensive to remove them from the back of the list, and so we can be
   * relatively aggressive about the former and conservative about the latter. In a "pack on
   * every clear" protocol, the best possible case is if we're always clearing at the very front
   * of the list. In this scenario, pack is always O(1). Conversely, the worst possible scenario
   * is when we're clearing at the *end* of the list. In this case, we won't actually remove any
   * cells until exactly half the list is emptied (thus, the number of empty cells is equal to
   * the number of full cells). In this case, the final pack is O(n), while the accumulated
   * wasted packs (which will fail to remove any items) will total to O((n / 2)^2). Thus, in the
   * worst case, we would need O((n / 2)^2 + n) operations to clear out the waste, where the
   * waste would be accumulated by n / 2 total clears, meaning that the marginal cost added to
   * clear is O(n/2 + 2), which is to say, O(n).
   *
   * In order to reduce this to a sub-linear cost, we need to pack less frequently, with higher
   * bounds, as the number of outstanding clears increases. Thus, rather than packing on each
   * clear, we should pack on the even log clears (1, 2, 4, 8, etc). For cases where most of the
   * churn is at the head of the list, this remains essentially O(1) and clears frequently. For
   * cases where the churn is buried deeper in the list, it becomes O(log n) per clear
   * (amortized). This still biases the optimizations towards the head of the list, but ensures
   * that packing will still inevitably reach all of the garbage cells.
   */
  def pack(bound: Int): Int =
    if (allowedToPack.compareAndSet(true, false)) {
      try {
        val got = head.get()
        if (got ne null)
          got.packHead(bound, 0, this)
        else
          0
      } finally {
        allowedToPack.set(true)
      }
    } else {
      0
    }

  override def toString(): String = s"CallbackStack($callback, ${get()})"

}

private object CallbackStack {
  @static def of[A](cb: A => Unit): CallbackStack[A] =
    new CallbackStack(cb)

  sealed abstract class Handle[A] {
    private[CallbackStack] def clear(): Unit
  }

  private[CallbackStack] final class Node[A](
      private[this] var callback: A => Unit
  ) extends Handle[A] {
    private[this] var next: Node[A] = _

    def getCallback(): A => Unit = callback

    def getNext(): Node[A] = next

    def setNext(next: Node[A]): Unit = {
      this.next = next
    }

    def clear(): Unit = {
      callback = null
    }

    /**
     * Packs this head node
     */
    @tailrec
    def packHead(bound: Int, removed: Int, root: CallbackStack[A]): Int = {
      val next = this.next // local copy

      if (callback == null) {
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
      val next = this.next // local copy

      if (callback == null) {
        // We own the pack lock, so it is safe to write `next`. It will be published to subsequent packs via the lock.
        // Concurrent readers ie `CallbackStack#apply` may read a stale value for `next` still pointing to this node.
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

    override def toString(): String = s"Node($callback, $next)"
  }
}
