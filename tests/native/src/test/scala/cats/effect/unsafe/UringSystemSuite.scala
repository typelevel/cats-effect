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
package unsafe

import cats.effect.unsafe.UringSystem.liburingOps._

import scala.scalanative.meta.LinktimeInfo

class UringSystemSuite extends BaseSuite {

  private[this] var uringRuntime: IORuntime = _

  override def runtime(): IORuntime = uringRuntime

  override def beforeAll(): Unit =
    if (LinktimeInfo.isLinux) {
      val (blocking, blockDown) =
        IORuntime.createDefaultBlockingExecutionContext(
          threadPrefix = s"io-blocking-${getClass.getName}")
      val (compute, api, compDown) =
        IORuntime.createWorkStealingComputeThreadPool(
          threadPrefix = s"io-compute-${getClass.getName}",
          blockerThreadPrefix = s"io-blocker-${getClass.getName}",
          pollingSystem = UringSystem
        )
      uringRuntime = IORuntime(
        compute,
        blocking,
        compute,
        List(api),
        { () =>
          compDown()
          blockDown()
        },
        IORuntimeConfig()
      )
    }

  override def afterAll(): Unit =
    if (uringRuntime ne null) uringRuntime.shutdown()

  real("start the UringSystem") {
    IO(assume(LinktimeInfo.isLinux, "UringSystem is only supported on Linux")) *>
      UringSystem.Uring.get.map(uring => assert(uring ne null))
  }

  real("submit a nop SQE and resume on completion") {
    IO(assume(LinktimeInfo.isLinux, "UringSystem is only supported on Linux")) *>
      UringSystem
        .Uring
        .get
        .flatMap { uring => uring.call(io_uring_prep_nop) }
        .map(rtn => assertEquals(rtn, 0))
  }
}
