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

package cats.effect.unsafe

import cats.effect.{BaseSuite, IO}
import cats.effect.testkit.TestInstances
import cats.syntax.all._

import scala.concurrent.duration._

class WorkerThreadNameSuite extends BaseSuite with TestInstances {

  private[this] var workerRuntime: IORuntime = _

  override protected def runtime(): IORuntime = workerRuntime

  override def beforeAll(): Unit = {
    val (blocking, blockDown) =
      IORuntime.createDefaultBlockingExecutionContext(threadPrefix =
        s"io-blocking-${getClass.getName}")
    val (scheduler, schedDown) =
      IORuntime.createDefaultScheduler(threadPrefix = s"io-scheduler-${getClass.getName}")
    val (compute, _, compDown) =
      IORuntime.createWorkStealingComputeThreadPool(
        threads = 1,
        threadPrefix = s"io-compute-${getClass.getName}",
        blockerThreadPrefix = s"io-blocker-${getClass.getName}",
        runtimeBlockingExpiration = 10.minutes)

    workerRuntime = IORuntime(
      compute,
      blocking,
      scheduler,
      { () =>
        compDown()
        blockDown()
        schedDown()
      },
      IORuntimeConfig()
    )
  }

  real("WorkerThread - rename itself when entering and exiting blocking region".ignore) {
    for {
      _ <- IO.cede
      computeThread <- threadInfo
      (computeThreadName, _) = computeThread
      blockerThread <- IO.blocking(threadInfo).flatten
      (blockerThreadName, blockerThreadId) = blockerThread
      _ <- IO.cede
      // The new worker (which replaced the thread which became a blocker) should also have a correct name
      newComputeThread <- threadInfo
      (newComputeThreadName, _) = newComputeThread
      // Force the previously blocking thread to become a compute thread by converting
      // the pool of compute threads (size=1) to blocker threads
      resetComputeThreads <- List.fill(2)(threadInfo <* IO.blocking(())).parSequence
    } yield {
      // Start with the regular prefix
      assert(computeThreadName.startsWith("io-compute"))
      // Correct WSTP index (threadCount is 1, so the only possible index is 0)
      assert(computeThreadName.endsWith("-0"))
      // Check that entering a blocking region changes the name
      assert(blockerThreadName.startsWith("io-blocker"))
      // Check that the replacement compute thread has correct name
      assert(newComputeThreadName.startsWith("io-compute"))
      // And index
      assert(newComputeThreadName.endsWith("-0"))
      // Check that the same thread is renamed again when it is readded to the compute pool
      val resetBlockerThread = resetComputeThreads.collectFirst {
        case (name, `blockerThreadId`) => name
      }
      assert(resetBlockerThread.nonEmpty, "blocker thread not found after reset")
      assert(
        resetBlockerThread.exists(_.startsWith("io-compute")),
        "blocker thread name was not reset")
      assert(
        resetBlockerThread.exists(_.endsWith("-0")),
        "blocker thread index was not correct")
    }
  }

  private val threadInfo =
    IO((Thread.currentThread().getName(), Thread.currentThread().getId()))

}
