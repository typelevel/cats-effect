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

package cats.effect
package unsafe

import scala.concurrent.ExecutionContext

// Can you imagine a thread pool on JS? Have fun trying to extend or instantiate
// this class. Unfortunately, due to the explicit branching, this type leaks
// into the shared source code of IOFiber.scala.
private[effect] sealed abstract class WorkStealingThreadPool private ()
    extends ExecutionContext {
  def execute(runnable: Runnable): Unit
  def reportFailure(cause: Throwable): Unit
  private[effect] def registerSuspendedFiber(): Unit
  private[effect] def deregisterSuspendedFiber(): Unit
  private[effect] def recordStartedFiber(): Unit
  private[effect] def executeFiber(fiber: IOFiber[_]): Unit
  private[effect] def rescheduleFiber(fiber: IOFiber[_]): Unit
  private[effect] def scheduleFiber(fiber: IOFiber[_]): Unit
}
