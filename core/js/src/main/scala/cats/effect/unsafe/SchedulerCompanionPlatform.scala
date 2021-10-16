/*
 * Copyright 2020-2022 Typelevel
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

import scala.concurrent.duration._
import scala.scalajs.js.timers

private[unsafe] abstract class SchedulerCompanionPlatform { this: Scheduler.type =>
  private[this] val maxTimeout = Int.MaxValue.millis

  def createDefaultScheduler(): (Scheduler, () => Unit) =
    (
      new Scheduler {

        def sleep(delay: FiniteDuration, task: Runnable): Runnable =
          if (delay <= maxTimeout) {
            val handle = timers.setTimeout(delay)(task.run())
            () => timers.clearTimeout(handle)
          } else {
            var cancel: Runnable = () => ()
            cancel = sleep(maxTimeout, () => cancel = sleep(delay - maxTimeout, task))
            () => cancel.run()
          }

        def nowMillis() = System.currentTimeMillis()
        def monotonicNanos() = System.nanoTime()
        override def nowMicros(): Long = nowMillis() * 1000
      },
      () => ())

}
