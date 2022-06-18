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
import scala.scalanative.runtime.ExecutionContext

import java.time.Instant
import java.time.temporal.ChronoField

private[effect] object GlobalBusyWaitScheduler extends Scheduler {
  
  def sleep(delay: FiniteDuration, task: Runnable): Runnable = {
    var canceled = false
    val when = monotonicNanos() + delay.toNanos
    lazy val go: Runnable = () =>
      if (canceled)
        ()
      else if (monotonicNanos() >= when)
        task.run()
      else
        ExecutionContext.global.execute(go)
    val _ = go
    () => canceled = true
  }

  def nowMillis() = System.currentTimeMillis()

  override def nowMicros(): Long = {
    val now = Instant.now()
    now.getEpochSecond * 1000000 + now.getLong(ChronoField.MICRO_OF_SECOND)
  }

  def monotonicNanos() = System.nanoTime()

}
