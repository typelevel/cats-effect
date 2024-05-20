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

package cats.effect.unsafe

import cats.effect.BaseSpec
import cats.effect.testkit.TestInstances

import scala.concurrent.duration._

class WorkerThreadNameSpec extends BaseSpec with TestInstances {

  override def runtime(): IORuntime = {
    lazy val rt: IORuntime = {
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

      IORuntime(
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

    rt
  }

  "WorkerThread" should {
    "rename itself when entering and exiting blocking region" in skipped(
      "this test is quite flaky in CI"
    ) /*real {
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
        computeThreadName must startWith("io-compute")
        // Correct WSTP index (threadCount is 1, so the only possible index is 0)
        computeThreadName must endWith("-0")
        // Check that entering a blocking region changes the name
        blockerThreadName must startWith("io-blocker")
        // Check that the replacement compute thread has correct name
        newComputeThreadName must startWith("io-compute")
        // And index
        newComputeThreadName must endWith("-0")
        // Check that the same thread is renamed again when it is readded to the compute pool
        val resetBlockerThread = resetComputeThreads.collectFirst {
          case (name, `blockerThreadId`) => name
        }
        resetBlockerThread must beSome[String].setMessage(
          "blocker thread not found after reset")
        resetBlockerThread must beSome((_: String).startsWith("io-compute"))
          .setMessage("blocker thread name was not reset")
        resetBlockerThread must beSome((_: String).endsWith("-0"))
          .setMessage("blocker thread index was not correct")
      }
    }*/
  }

  /*private val threadInfo =
    IO((Thread.currentThread().getName(), Thread.currentThread().getId()))*/

}
