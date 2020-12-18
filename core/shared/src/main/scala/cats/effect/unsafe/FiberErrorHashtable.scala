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
package unsafe

private[effect] final class FiberErrorHashtable(initialSize: Int) {
  var hashtable: Array[Throwable => Unit] = new Array(initialSize)
  private[this] var capacity = 0
  private[this] var mask = initialSize - 1

  def put(cb: Throwable => Unit): Unit =
    this.synchronized {
      val len = hashtable.length
      if (capacity == len) {
        val newLen = len * 2
        val newHashtable = new Array[Throwable => Unit](newLen)
        System.arraycopy(hashtable, 0, newHashtable, 0, len)
        hashtable = newHashtable
        mask = newLen - 1
      }

      val init = hash(cb)
      var idx = init
      var cont = true
      while (cont) {
        if (hashtable(idx) == null) {
          hashtable(idx) = cb
          capacity += 1
          cont = false
        } else {
          idx += 1
          idx &= mask
        }
      }
    }

  def remove(cb: Throwable => Unit): Unit =
    this.synchronized {
      val init = hash(cb)
      var idx = init
      var cont = true
      while (cont) {
        if (cb eq hashtable(idx)) {
          hashtable(idx) = null
          capacity -= 1
          cont = false
        } else {
          idx += 1
          idx &= mask
          if (idx == init - 1) {
            cont = false
          }
        }
      }
    }

  private def hash(cb: Throwable => Unit): Int =
    cb.hashCode() & mask
}
