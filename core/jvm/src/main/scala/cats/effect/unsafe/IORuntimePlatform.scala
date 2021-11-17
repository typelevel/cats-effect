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

import cats.effect.tracing.TracingConstants._
import cats.effect.unsafe.metrics.LiveFiberSnapshotTrigger

import java.lang.management.ManagementFactory
import javax.management.ObjectName

private[unsafe] class IORuntimePlatform { self: IORuntime =>

  protected def registerFiberMonitorMBean(fiberMonitor: FiberMonitor): () => Unit = {
    if (isStackTracing) {
      val mBeanServer =
        try ManagementFactory.getPlatformMBeanServer()
        catch {
          case t: Throwable =>
            t.printStackTrace()
            null
        }

      if (mBeanServer ne null) {
        val hash = System.identityHashCode(fiberMonitor).toHexString

        try {
          val liveFiberSnapshotTriggerName = new ObjectName(
            s"cats.effect.unsafe.metrics:type=LiveFiberSnapshotTrigger-$hash")
          val liveFiberSnapshotTrigger = new LiveFiberSnapshotTrigger(fiberMonitor)
          mBeanServer.registerMBean(liveFiberSnapshotTrigger, liveFiberSnapshotTriggerName)

          () => mBeanServer.unregisterMBean(liveFiberSnapshotTriggerName)
        } catch {
          case t: Throwable =>
            t.printStackTrace()
            () => ()
        }
      } else () => ()
    } else () => ()
  }
}
