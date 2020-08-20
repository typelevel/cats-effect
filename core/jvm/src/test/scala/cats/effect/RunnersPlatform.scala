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

import cats.effect.unsafe.IORuntime

import org.specs2.specification.BeforeAfterAll

trait RunnersPlatform extends BeforeAfterAll {

  private[this] var runtime0: IORuntime = _

  protected def runtime(): IORuntime = runtime0

  def beforeAll(): Unit = {
    val (blk, disp2) =
      IORuntime.createDefaultBlockingExecutionContext(s"io-blocking-${getClass.getName}")
    val (sched, disp3) = IORuntime.createDefaultScheduler(s"io-scheduler-${getClass.getName}")
    val (ctx, disp1) =
      IORuntime.createDefaultComputeExecutionContext(runtime0)

    runtime0 = IORuntime(
      ctx,
      blk,
      sched,
      { () =>
        disp1()
        disp2()
        disp3()
      })
  }

  def afterAll(): Unit = runtime().shutdown()
}
