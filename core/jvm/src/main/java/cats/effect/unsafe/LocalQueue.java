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

package cats.effect.unsafe;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

@SuppressWarnings("unused")
class HeadPadding {
  private final int hp00 = 0;
  private final long hp01 = 0;
  private final long hp02 = 0;
  private final long hp03 = 0;
  private final long hp04 = 0;
  private final long hp05 = 0;
  private final long hp06 = 0;
  private final long hp07 = 0;
  private final long hp08 = 0;
  private final long hp09 = 0;
  private final long hp10 = 0;
  private final long hp11 = 0;
  private final long hp12 = 0;
  private final long hp13 = 0;
  private final long hp14 = 0;
  private final long hp15 = 0;
  private final long hp16 = 0;
}

@SuppressWarnings("unused")
class Head extends HeadPadding {
  protected static final AtomicIntegerFieldUpdater<Head> updater =
      AtomicIntegerFieldUpdater.newUpdater(Head.class, "head");

  /**
   * The head of the queue.
   *
   * <p>Concurrently updated by many [[WorkerThread]] s.
   *
   * <p>Conceptually, it is a concatenation of two unsigned 16 bit values. Since the capacity of the
   * local queue is less than (2^16 - 1), the extra unused values are used to distinguish between
   * the case where the queue is empty (`head` == `tail`) and (`head` - `tail` ==
   * [[LocalQueueConstants.LocalQueueCapacity]]), which is an important distinction for other
   * [[WorkerThread]] s trying to steal work from the queue.
   *
   * <p>The least significant 16 bits of the integer value represent the ''real'' value of the head,
   * pointing to the next [[cats.effect.IOFiber]] instance to be dequeued from the queue.
   *
   * <p>The most significant 16 bits of the integer value represent the ''steal'' tag of the head.
   * This value is altered by another [[WorkerThread]] which has managed to win the race and become
   * the exclusive ''stealer'' of the queue. During the period in which the ''steal'' tag differs
   * from the ''real'' value, no other [[WorkerThread]] can steal from the queue, and the owner
   * [[WorkerThread]] also takes special care to not mangle the ''steal'' tag set by the
   * ''stealer''. The stealing [[WorkerThread]] is free to transfer half of the available
   * [[cats.effect.IOFiber]] object references from this queue into its own [[LocalQueue]] during
   * this period, making sure to undo the changes to the ''steal'' tag of the head on completion,
   * action which ultimately signals that stealing is finished.
   */
  private volatile int head = 0;
}

@SuppressWarnings("unused")
class TailPadding extends Head {
  private final int tp00 = 0;
  private final long tp01 = 0;
  private final long tp02 = 0;
  private final long tp03 = 0;
  private final long tp04 = 0;
  private final long tp05 = 0;
  private final long tp06 = 0;
  private final long tp07 = 0;
  private final long tp08 = 0;
  private final long tp09 = 0;
  private final long tp10 = 0;
  private final long tp11 = 0;
  private final long tp12 = 0;
  private final long tp13 = 0;
  private final long tp14 = 0;
  private final long tp15 = 0;
  private final long tp16 = 0;
}

@SuppressWarnings("unused")
class Tail extends TailPadding {
  protected static final AtomicIntegerFieldUpdater<Tail> updater =
      AtomicIntegerFieldUpdater.newUpdater(Tail.class, "tailPublisher");

  /**
   * The tail of the queue.
   *
   * <p>Only ever updated by the owner [[WorkerThread]], but also read by other threads to determine
   * the current size of the queue, for work stealing purposes. Denotes the next available free slot
   * in the `buffer` array.
   *
   * <p>Conceptually, it is an unsigned 16 bit value (the most significant 16 bits of the integer
   * value are ignored in most operations).
   */
  protected int tail = 0;

  private volatile int tailPublisher = 0;
}

@SuppressWarnings("unused")
class LocalQueuePadding extends Tail {
  private final int qp00 = 0;
  private final long qp01 = 0;
  private final long qp02 = 0;
  private final long qp03 = 0;
  private final long qp04 = 0;
  private final long qp05 = 0;
  private final long qp06 = 0;
  private final long qp07 = 0;
  private final long qp08 = 0;
  private final long qp09 = 0;
  private final long qp10 = 0;
  private final long qp11 = 0;
  private final long qp12 = 0;
  private final long qp13 = 0;
  private final long qp14 = 0;
  private final long qp15 = 0;
  private final long qp16 = 0;
}
