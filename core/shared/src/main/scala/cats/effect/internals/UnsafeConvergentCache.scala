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

//
//package cats.effect.internals
//
//import scala.annotation.tailrec
//
//private[internals] final class UnsafeConvergentCache[K, V <: AnyRef] {
//
//  private[this] var buffer: Buffer = new Buffer(10)
//
//  // We need final-field semantics when:
//  // 1) Growing an array and replacing the reference
//  // 2) Inserting a new cache entry
//  def put(k: K, v: V): Unit = {
//    if (buffer.put(k, v) ne null) {
//      buffer = buffer.grow(k, v)
//    }
//    ()
//  }
//
//  def get(k: K): V =
//    buffer.get(k)
//
//  private[this] final class Buffer(val logSize: Int) {
//    private[this] val size = 1 << logSize
//    private[this] val mask = size - 1
//    private[this] val array = new Array[AnyRef](size)
//
//    def put(k: K, v: V): V = {
//      val hash = k.hashCode() & mask
//      val old = array(hash)
//      if (old ne null) {
//        array(hash) = v
//      }
//      old.asInstanceOf[V]
//    }
//
//    def grow(k: K, v: V): Buffer = {
//      @tailrec
//      def go(logSize: Int): Buffer = {
//        // grow and reset buffer
//        val newBuffer = new Buffer(logSize)
//        newBuffer.put(k, v)
//
//        var collision = false
//        var i = 0
//
//        while (!collision && i < size) {
//          val elem = array(i)
//          if (elem ne null) {
//
//          } else {
//            collision = true
//          }
//
//          i += 1
//        }
//
//        if (logSize)
//      }
//
//      go(logSize + 1)
//    }
//
//    def get(k: K): V = {
//      val hash = k.hashCode() & mask
//      array(hash).asInstanceOf[V]
//    }
//  }
//
//}
