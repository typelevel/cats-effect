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

package cats.effect

import cats.effect.unsafe.{IORuntime, IORuntimeConfig}

trait RunnersPlatform { self: munit.Suite =>

  private[this] var runtime0: IORuntime = _

  protected def runtime(): IORuntime = runtime0

  override def beforeAll(): Unit = {
    val (blocking, blockDown) =
      IORuntime.createDefaultBlockingExecutionContext(threadPrefix =
        s"io-blocking-${getClass.getName}")
    val (compute, poller, compDown) =
      IORuntime.createWorkStealingComputeThreadPool(
        threadPrefix = s"io-compute-${getClass.getName}",
        blockerThreadPrefix = s"io-blocker-${getClass.getName}")

    runtime0 = IORuntime(
      compute,
      blocking,
      compute,
      List(poller),
      { () =>
        compDown()
        blockDown()
      },
      IORuntimeConfig()
    )
  }

  override def afterAll(): Unit = runtime().shutdown()
}
