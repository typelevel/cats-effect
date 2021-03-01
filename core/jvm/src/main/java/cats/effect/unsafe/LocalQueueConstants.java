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

/**
 * Contains static constants used throughout the implementation of the
 * [[cats.effect.unsafe.LocalQueue]].
 */
class LocalQueueConstants {

  /**
   * Fixed capacity of the [[cats.effect.unsafe.LocalQueue]] implementation,
   * empirically determined to provide a balance between memory footprint and
   * enough headroom in the face of bursty workloads which spawn a lot of fibers
   * in a short period of time.
   * 
   * Must be a power of 2.
   */
  static final int LocalQueueCapacity = 256;

  /**
   * Bitmask used for indexing into the circular buffer.
   */
  static final int LocalQueueCapacityMask = LocalQueueCapacity - 1;

  /**
   * Half of the local queue capacity.
   */
  static final int HalfLocalQueueCapacity = LocalQueueCapacity / 2;

  /**
   * Overflow fiber batch size.
   */
  static final int OverflowBatchSize = HalfLocalQueueCapacity + 1;

  /**
   * Bitmask used to extract the 16 least significant bits of a 32 bit integer
   * value.
   */
  static final int UnsignedShortMask = (1 << 16) - 1;
}
