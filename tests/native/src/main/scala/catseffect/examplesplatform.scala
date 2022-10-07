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

package catseffect

import cats.effect.{IO, IOApp}
import cats.effect.std.Random
import cats.effect.unsafe.IORuntimeConfig
import cats.syntax.all._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

package object examples {
  def exampleExecutionContext = ExecutionContext.global

  // The parameters here were chosen experimentally and seem to be
  // relatively reliable. The trick is finding a balance such that
  // we end up with every WSTP thread being blocked but not permanently
  // so that the starvation detector fiber still gets to run and
  // therefore report its warning
  object CpuStarvation extends IOApp.Simple {

    override protected def runtimeConfig: IORuntimeConfig = IORuntimeConfig().copy(
      cpuStarvationCheckInterval = 200.millis,
      cpuStarvationCheckInitialDelay = 0.millis,
      cpuStarvationCheckThreshold = 0.2d
    )

    val run = Random.scalaUtilRandom[IO].flatMap { rand =>
      // jitter to give the cpu starvation checker a chance to run at all
      val jitter = rand.nextIntBounded(100).flatMap(n => IO.sleep(n.millis))
      (jitter >> IO(Thread.sleep(400)))
        .replicateA_(10)
        .parReplicateA_(Runtime.getRuntime().availableProcessors() * 2)
    }
  }
}
