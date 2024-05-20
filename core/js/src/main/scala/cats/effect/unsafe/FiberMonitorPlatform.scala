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

package cats.effect
package unsafe

import scala.concurrent.ExecutionContext
import scala.scalajs.{js, LinkingInfo}

private[effect] abstract class FiberMonitorPlatform {
  def apply(compute: ExecutionContext): FiberMonitor = {
    if (LinkingInfo.developmentMode && weakRefsAvailable) {
      if (compute.isInstanceOf[BatchingMacrotaskExecutor]) {
        val bmec = compute.asInstanceOf[BatchingMacrotaskExecutor]
        new FiberMonitorImpl(bmec)
      } else {
        new FiberMonitorImpl(null)
      }
    } else {
      new NoOpFiberMonitor()
    }
  }

  private[this] final val Undefined = "undefined"

  /**
   * Feature-tests for all the required, well, features :)
   */
  private[unsafe] def weakRefsAvailable: Boolean =
    js.typeOf(js.Dynamic.global.WeakRef) != Undefined &&
      js.typeOf(js.Dynamic.global.FinalizationRegistry) != Undefined
}
