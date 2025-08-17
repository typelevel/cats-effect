/*
 * Copyright 2020-2025 Typelevel
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

import scala.scalanative.libc.stdatomic._
import scala.scalanative.libc.stdatomic.memory_order.memory_order_release
import scala.scalanative.runtime.{fromRawPtr, Intrinsics}

// native mirror of LocalQueue.java
private class Head {

  /**
   * The head of the queue.
   *
   * <p>Concurrently updated by many [[WorkerThread]] s.
   *
   * <p>Conceptually, it is a concatenation of two unsigned 16 bit values. Since the capacity of
   * the local queue is less than (2^16 - 1), the extra unused values are used to distinguish
   * between the case where the queue is empty (`head` == `tail`) and (`head` - `tail` ==
   * [[LocalQueueConstants.LocalQueueCapacity]]), which is an important distinction for other
   * [[WorkerThread]] s trying to steal work from the queue.
   *
   * <p>The least significant 16 bits of the integer value represent the ''real'' value of the
   * head, pointing to the next [[cats.effect.IOFiber]] instance to be dequeued from the queue.
   *
   * <p>The most significant 16 bits of the integer value represent the ''steal'' tag of the
   * head. This value is altered by another [[WorkerThread]] which has managed to win the race
   * and become the exclusive ''stealer'' of the queue. During the period in which the ''steal''
   * tag differs from the ''real'' value, no other [[WorkerThread]] can steal from the queue,
   * and the owner [[WorkerThread]] also takes special care to not mangle the ''steal'' tag set
   * by the ''stealer''. The stealing [[WorkerThread]] is free to transfer half of the available
   * [[cats.effect.IOFiber]] object references from this queue into its own [[LocalQueue]]
   * during this period, making sure to undo the changes to the ''steal'' tag of the head on
   * completion, action which ultimately signals that stealing is finished.
   */
  @volatile
  protected var head: Int = 0

  {
    // prevent unused warnings
    head = 0
  }
}

private object Head {
  private[unsafe] object updater {

    def get(obj: Head): Int =
      fromRawPtr[atomic_int](Intrinsics.classFieldRawPtr[Head](obj, "head")).atomic.load()

    def compareAndSet(obj: Head, oldHd: Int, newHd: Int): Boolean =
      fromRawPtr[atomic_int](Intrinsics.classFieldRawPtr[Head](obj, "head"))
        .atomic
        .compareExchangeStrong(oldHd, newHd)
  }
}

private class Tail extends Head {

  /**
   * The tail of the queue.
   *
   * <p>Only ever updated by the owner [[WorkerThread]], but also read by other threads to
   * determine the current size of the queue, for work stealing purposes. Denotes the next
   * available free slot in the `buffer` array.
   *
   * <p>Conceptually, it is an unsigned 16 bit value (the most significant 16 bits of the
   * integer value are ignored in most operations).
   */
  protected var tail: Int = 0

  @volatile
  private[this] var tailPublisher: Int = 0

  {
    // prevent unused warnings
    tailPublisher = 0
  }
}

private object Tail {

  private[unsafe] object updater {

    def get(obj: Tail): Int =
      fromRawPtr[atomic_int](Intrinsics.classFieldRawPtr[Tail](obj, "tail")).atomic.load()

    def lazySet(obj: Tail, newValue: Int): Unit =
      fromRawPtr[atomic_int](Intrinsics.classFieldRawPtr[Tail](obj, "tail"))
        .atomic
        .store(newValue, memory_order_release)
  }
}

private class LocalQueuePadding extends Tail
