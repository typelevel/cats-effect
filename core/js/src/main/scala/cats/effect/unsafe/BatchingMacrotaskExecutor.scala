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

package cats.effect
package unsafe

import org.scalajs.macrotaskexecutor.MacrotaskExecutor

import scala.concurrent.ExecutionContextExecutor
import scala.util.control.NonFatal

import java.util.ArrayDeque

private final class BatchingMacrotaskExecutor(pollEvery: Int) extends ExecutionContextExecutor {

  private[this] var needsReschedule: Boolean = true

  private[this] val executeQueue: ArrayDeque[Runnable] = new ArrayDeque

  private[this] def executeTask = _executeTask
  private[this] val _executeTask: Runnable = () => {
    // do up to pollEvery tasks
    var i = 0
    while (i < pollEvery && !executeQueue.isEmpty()) {
      val runnable = executeQueue.poll()
      try runnable.run()
      catch {
        case NonFatal(t) => reportFailure(t)
        case t: Throwable => IOFiber.onFatalFailure(t)
      }
      i += 1
    }

    if (!executeQueue.isEmpty()) // we'll be right back after the (post) message
      MacrotaskExecutor.execute(executeTask)
    else
      needsReschedule = true
  }

  def reportFailure(t: Throwable): Unit = t.printStackTrace()

  def execute(runnable: Runnable): Unit = {
    executeQueue.addLast(runnable)
    if (needsReschedule) {
      needsReschedule = false
      MacrotaskExecutor.execute(executeTask)
    }
  }

}
