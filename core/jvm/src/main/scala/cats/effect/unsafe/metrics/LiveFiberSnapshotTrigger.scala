/*
 * Copyright 2020-2024 Typelevel
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
package metrics

import scala.collection.mutable.ArrayBuffer

/**
 * An implementation of the [[LiveFiberSnapshotTriggerMBean]] interface which simply delegates
 * to the corresponding method of the backing [[cats.effect.unsafe.FiberMonitor]].
 *
 * @param monitor
 *   the backing fiber monitor
 */
private[unsafe] final class LiveFiberSnapshotTrigger(monitor: FiberMonitor)
    extends LiveFiberSnapshotTriggerMBean {
  def liveFiberSnapshot(): Array[String] = {
    val buffer = new ArrayBuffer[String]
    monitor.liveFiberSnapshot(buffer += _)
    buffer.toArray
  }
}
