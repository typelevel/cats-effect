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

package cats.effect.unsafe;

final class WorkStealingThreadPoolConstants {

  // The number of unparked threads is encoded as an unsigned 16 bit number
  // in the 16 most significant bits of a 32 bit integer.
  public static final int UnparkShift = 16;

  // The number of threads currently searching for work is encoded as an
  // unsigned 16 bit number in the 16 least significant bits of a
  // 32 bit integer. Used for extracting the number of searching threads.
  public static final int SearchMask = (1 << UnparkShift) - 1;

  // Used for extracting the number of unparked threads.
  public static final int UnparkMask = ~SearchMask;

  // Used for checking for work from the external queue every few iterations.
  public static final int ExternalCheckIterations = 64;
  public static final int ExternalCheckIterationsMask = ExternalCheckIterations - 1;
}
