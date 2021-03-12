/*
 * Copyright 2020-2021 Typelevel
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
 * A striped queue implementation inspired by the
 * [[https://scal.cs.uni-salzburg.at/dq/ Scal]] project. The whole queue
 * consists of several [[java.util.concurrent.ConcurrentLinkedQueue]] instances
 * (the number of queues is a power of 2 for optimization purposes) which are
 * load balanced using random index generation.
 *
 * @param threadCount the number of threads to load balance
 */
private final class ScalQueue[A <: AnyRef](threadCount: Int) {

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

  private[this] val numQueues: Int = mask + 1

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

  def offer(a: A, random: ThreadLocalRandom): Unit = {
    val idx = random.nextInt(numQueues)
    queues(idx).offer(a)
    ()
  }

  def offerAll(as: Array[A], random: ThreadLocalRandom): Unit = {
    val nq = numQueues
    val len = as.length
    var i = 0
    while (i < len) {
      val fiber = as(i)
      if (fiber ne null) {
        val idx = random.nextInt(nq)
        queues(idx).offer(fiber)
      }
      i += 1
    }
  }

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

  def nonEmpty(): Boolean =
    !isEmpty()

  def clear(): Unit = {
    val nq = numQueues
    var i = 0
    while (i < nq) {
      queues(i).clear()
      i += 1
    }
  }
}
