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

import scala.concurrent.duration._

import cats.effect.unsafe.{IORuntime, IORuntimeConfig, Scheduler}

import org.specs2.specification.BeforeAfterAll

trait RunnersPlatform extends BeforeAfterAll {

  private[this] var runtime0: IORuntime = _

  protected def runtime(): IORuntime = runtime0

  def schedulerResolution: FiniteDuration = 100.millis

  def beforeAll(): Unit = {
    val (blocking, blockDown) =
      IORuntime.createDefaultBlockingExecutionContext(threadPrefix =
        s"io-blocking-${getClass.getName}")

    val (scheduler, schedDown) =
      Scheduler.createDefaultScheduler(schedulerResolution)

    val (compute, compDown) =
      IORuntime.createDefaultComputeThreadPool(
        runtime0,
        threadPrefix = s"io-compute-${getClass.getName}")

    runtime0 = new IORuntime(
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

  def afterAll(): Unit = runtime().shutdown()
}
