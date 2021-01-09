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

final class LocalQueueConstants {
  
  // Fixed capacity of the local work queue (power of 2).
  public static final int LocalQueueCapacity = 256;

  // Mask for modulo operations using bitwise shifting.
  public static final int CapacityMask = LocalQueueCapacity - 1;

  // Mask for extracting the 16 least significant bits of a 32 bit integer.
  // Used to represent unsigned 16 bit integers.
  public static final int UnsignedShortMask = (1 << 16) - 1;

  // Half of the local work queue capacity.
  public static final int HalfLocalQueueCapacity = LocalQueueCapacity / 2;

  // Half of the local work queue and the new fiber gets offloaded to the external queue.
  public static final int BatchLength = HalfLocalQueueCapacity + 1;
}