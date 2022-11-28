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
import scala.scalajs.concurrent.QueueExecutionContext

private[effect] final class BatchingMacrotaskExecutor(batchSize: Int)
    extends ExecutionContextExecutor {

  private[this] val MicrotaskExecutor = QueueExecutionContext.promises()

  private[this] var counter = 0

  private[this] val resetCounter: Runnable = () => counter = 0

  def reportFailure(t: Throwable): Unit = MacrotaskExecutor.reportFailure(t)

  def execute(runnable: Runnable): Unit =
    MacrotaskExecutor.execute(runnable)

  def schedule(runnable: Runnable): Unit = {
    if (counter < batchSize == 0) {
      MicrotaskExecutor.execute(runnable)
    } else {
      if (counter == batchSize)
        MacrotaskExecutor.execute(resetCounter)
      MacrotaskExecutor.execute(runnable)
    }
    counter += 1
  }

}
