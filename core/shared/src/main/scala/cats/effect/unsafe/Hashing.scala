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

private[effect] object Hashing {
  // we expect to have at least this many tables (it will be rounded up to a power of two by the log2NumTables computation)
  private[this] def minNumTables: Int = Runtime.getRuntime().availableProcessors() * 4

  final val log2NumTables: Int = {
    var result = 0
    var x = minNumTables
    while (x != 0) {
      result += 1
      x >>= 1
    }
    result
  }
}
