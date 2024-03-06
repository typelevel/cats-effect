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
import scala.concurrent.duration.FiniteDuration

// Can you imagine a thread pool on JS? Have fun trying to extend or instantiate
// this class. Unfortunately, due to the explicit branching, this type leaks
// into the shared source code of IOFiber.scala.
private[effect] sealed abstract class WorkStealingThreadPool[P] private ()
    extends ExecutionContext {
  def execute(runnable: Runnable): Unit
  def reportFailure(cause: Throwable): Unit
  private[effect] def reschedule(runnable: Runnable): Unit
  private[effect] def sleepInternal(
      delay: FiniteDuration,
      callback: Right[Nothing, Unit] => Unit): Function0[Unit] with Runnable
  private[effect] def sleep(
      delay: FiniteDuration,
      task: Runnable,
      fallback: Scheduler): Runnable
  private[effect] def canExecuteBlockingCode(): Boolean
  private[effect] def prepareForBlocking(): Unit
  private[unsafe] def liveTraces(): (
      Map[Runnable, Trace],
      Map[WorkerThread[P], (Thread.State, Option[(Runnable, Trace)], Map[Runnable, Trace])],
      Map[Runnable, Trace])
}

private[unsafe] sealed abstract class WorkerThread[P] private () extends Thread {
  private[unsafe] def isOwnedBy(threadPool: WorkStealingThreadPool[_]): Boolean
  private[unsafe] def monitor(fiber: Runnable): WeakBag.Handle
  private[unsafe] def index: Int
}
