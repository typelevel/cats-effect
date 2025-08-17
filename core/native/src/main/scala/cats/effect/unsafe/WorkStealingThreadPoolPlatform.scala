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

package cats.effect
package unsafe

import scala.scalanative.libc.errno._
import scala.scalanative.libc.string._
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.posix.time._
import scala.scalanative.posix.timeOps._
import scala.scalanative.unsafe._

trait WorkStealingThreadPoolPlatform[P <: AnyRef] extends Scheduler {
  this: WorkStealingThreadPool[P] =>

  // TODO cargo culted from EventLoopExecutorScheduler.scala
  override def nowMicros(): Long =
    if (LinktimeInfo.isFreeBSD || LinktimeInfo.isLinux || LinktimeInfo.isMac) {
      val ts = stackalloc[timespec]()
      if (clock_gettime(CLOCK_REALTIME, ts) != 0)
        throw new RuntimeException(fromCString(strerror(errno)))
      ts.tv_sec.toLong * 1000000 + ts.tv_nsec.toLong / 1000
    } else {
      super.nowMicros()
    }
}
