/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

package cats.effect.internals

private[internals] final class UnsafeConvergentCache[K, V <: AnyRef] {

  private[this] val buffer: Buffer = new Buffer(10)

  def put(k: K, v: V): Unit =
    buffer.put(k, v)

  def get(k: K): V =
    buffer.get(k)

  private[this] final class Buffer(logSize: Int) {
    private[this] val size = 1 << logSize
    private[this] val mask = size - 1
    private[this] val array = new Array[AnyRef](size)

    def put(k: K, v: V): Unit = {
      val hash = k.hashCode() & mask
      array(hash) = v
//      if (array(hash) ne null) {
//        array(hash) = v
//      } else {
//        // grow and reset buffer
//        val newBuffer = new Buffer(logSize + 1)
//        for (i <- 0 until size) {
//          val elem = array(i)
//          if (elem ne null) {
//
//          }
//        }
//        buffer = newBuffer
//      }
    }

    def get(k: K): V = {
      val hash = k.hashCode() & mask
      array(hash).asInstanceOf[V]
    }
  }

}
