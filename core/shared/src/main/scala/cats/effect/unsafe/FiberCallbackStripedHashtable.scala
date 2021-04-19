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

import java.util.concurrent.ThreadLocalRandom

private[effect] final class FiberCallbackStripedHashtable {
  private[this] val numTables: Int = Runtime.getRuntime().availableProcessors()
  private[this] val initialCapacity: Int = 8
  private[this] val tables: Array[ThreadSafeHashtable] = {
    val array = new Array[ThreadSafeHashtable](numTables)
    var i = 0
    while (i < numTables) {
      array(i) = new ThreadSafeHashtable(initialCapacity)
      i += 1
    }
    array
  }

  def put(cb: Throwable => Unit, random: ThreadLocalRandom): Int = {
    val idx = random.nextInt(numTables)
    tables(idx).put(cb)
    idx
  }

  def remove(cb: Throwable => Unit, idx: Int): Unit = {
    tables(idx).remove(cb)
  }
}
