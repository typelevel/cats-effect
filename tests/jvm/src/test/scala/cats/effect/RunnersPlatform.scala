/*
 * Copyright 2020 Typelevel
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

import cats.effect.unsafe.{IORuntime, IORuntimeConfig}

import org.specs2.specification.BeforeAfterAll

trait RunnersPlatform extends BeforeAfterAll {

  private[this] var runtime0: IORuntime = _

  protected def runtime(): IORuntime = runtime0

  def beforeAll(): Unit = {
    val cancellationCheckThreshold =
      System.getProperty("cats.effect.cancellation.check.threshold", "512").toInt

    val (blocking, blockDown) =
      IORuntime.createDefaultBlockingExecutionContext(s"io-blocking-${getClass.getName}")
    val (scheduler, schedDown) =
      IORuntime.createDefaultScheduler(s"io-scheduler-${getClass.getName}")
    val (compute, compDown) =
      IORuntime.createDefaultComputeThreadPool(runtime0, s"io-compute-${getClass.getName}")

    runtime0 = new IORuntime(
      compute,
      blocking,
      scheduler,
      () => (),
      IORuntimeConfig(
        cancellationCheckThreshold,
        System
          .getProperty("cats.effect.auto.yield.threshold.multiplier", "2")
          .toInt * cancellationCheckThreshold
      ),
      internalShutdown = () => {
        compDown()
        blockDown()
        schedDown()
      }
    )
  }

  def afterAll(): Unit = runtime().shutdown()
}
