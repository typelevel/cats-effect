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

package cats.effect

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

// analogous to ArrayStack, but thread-safe and atomic
// separated so as to avoid unnecessary memory barriers
// note that there is no verification to ensure safety of
// simultaneous pushes and pops
final private[effect] class SafeArrayStack[A <: AnyRef](initBound: Int) {

  private[this] val buffer = new AtomicReference[Array[AnyRef]](new Array[AnyRef](initBound))
  private[this] val index = new AtomicInteger(0)

  def push(a: A): Unit = {
    val index = checkAndGrow()
    buffer.get()(index) = a
  }

  def pop(): A = {
    val i = index.getAndDecrement()
    val back = buffer.get()(i).asInstanceOf[A]
    buffer.get()(i) = null
    back
  }

  def unsafeBuffer(): Array[A] = buffer.get().asInstanceOf[Array[A]]

  // for safe operation, always get the index *first*, since the buffer will never shrink
  def unsafeIndex(): Int = index.get()

  private[this] def checkAndGrow(): Int = {
    // claim an index, atomically
    val back = index.getAndIncrement()

    def loop(): Int = {
      val arr = buffer.get()
      val len = arr.length

      if (back == len) {
        val arr2 = new Array[AnyRef](len * 2)
        System.arraycopy(arr, 0, arr2, 0, len)
        if (!buffer.compareAndSet(arr, arr2))
          loop()
        else
          back
      } else if (back > len) {
        // someone else is in the process of resizing, busy-wait until they're done
        loop()
      } else {
        // we got it and we're good to go!
        back
      }
    }

    loop()
  }
}
