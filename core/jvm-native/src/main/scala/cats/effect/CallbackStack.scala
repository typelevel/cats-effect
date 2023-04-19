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

import scala.annotation.tailrec

import java.util.concurrent.atomic.AtomicReference

private final class CallbackStack[A](private[this] var callback: A => Unit)
    extends AtomicReference[CallbackStack[A]] {

  def push(next: A => Unit): CallbackStack[A] = {
    val attempt = new CallbackStack(next)

    @tailrec
    def loop(): CallbackStack[A] = {
      val cur = get()
      attempt.lazySet(cur)

      if (!compareAndSet(cur, attempt))
        loop()
      else
        attempt
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
  @tailrec
  def apply(oc: A, invoked: Boolean): Boolean = {
    val cb = callback

    val invoked2 = if (cb != null) {
      cb(oc)
      true
    } else {
      invoked
    }

    val next = get()
    if (next != null)
      next(oc, invoked2)
    else
      invoked2
  }

  /**
   * Removes the current callback from the queue.
   */
  def clearCurrent(handle: CallbackStack.Handle): Unit = {
    val _ = handle
    callback = null
  }

  def currentHandle(): CallbackStack.Handle = 0

  def clear(): Unit = lazySet(null)

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
  def pack(bound: Int): Int = {
    // the first cell is always retained
    val got = get()
    if (got ne null)
      got.packInternal(bound, 0, this)
    else
      0
  }

  @tailrec
  private def packInternal(bound: Int, removed: Int, parent: CallbackStack[A]): Int = {
    if (callback == null) {
      val child = get()

      // doing this cas here ultimately deoptimizes contiguous empty chunks
      if (!parent.compareAndSet(this, child)) {
        // if we're contending with another pack(), just bail and let them continue
        removed
      } else {
        if (child == null) {
          // bottomed out
          removed
        } else {
          // note this can cause the bound to go negative, which is fine
          child.packInternal(bound - 1, removed + 1, parent)
        }
      }
    } else {
      val child = get()
      if (child == null) {
        // bottomed out
        removed
      } else {
        if (bound > 0)
          child.packInternal(bound - 1, removed, this)
        else
          removed
      }
    }
  }
}

private object CallbackStack {
  def apply[A](cb: A => Unit): CallbackStack[A] =
    new CallbackStack(cb)

  type Handle = Byte
}
