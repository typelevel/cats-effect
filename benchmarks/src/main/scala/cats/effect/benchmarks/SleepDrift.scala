/*
 * Copyright 2020-2023 Typelevel
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
package benchmarks

import cats.syntax.all._

import scala.concurrent.duration._

object SleepDrift extends IOApp.Simple {

  override val runtimeConfig =
    super.runtimeConfig.copy(cpuStarvationCheckInitialDelay = Duration.Inf)

  val delayTwoMinutes = {
    def loop(n: Int): IO[Unit] = {
      if (n <= 0)
        IO.unit
      else
        IO.sleep(1.millis) >> loop(n - 1)
    }

    loop((2.minutes / 1.millis).toInt)
  }

  val createPressure = {
    def fiber(i: Int): IO[Int] =
      IO.cede.flatMap { _ =>
        IO(i).flatMap { j =>
          IO.cede.flatMap { _ =>
            if (j > 10000)
              IO.cede.flatMap(_ => IO.pure(j))
            else
              IO.cede.flatMap(_ => fiber(j + 1))
          }
        }
      }

    val allocate = List.range(0, 1000000).traverse(fiber(_).start)

    allocate.bracket(_.traverse(_.joinWithNever))(_.traverse_(_.cancel)).map(_.sum)
  }

  val measure =
    for {
      start <- IO.monotonic
      _ <- delayTwoMinutes
      end <- IO.monotonic
    } yield ((end - start) / 2.minutes) - 1

  val run =
    for {
      _ <- IO.println("Warming up...")
      _ <- delayTwoMinutes // warmup

      _ <- IO.println("Measuring unloaded...")
      unloadedOverhead <- measure
      _ <- IO.println(s"Unloaded overhead: $unloadedOverhead\n")

      _ <- IO.println("Measuring heavily loaded...")
      loadedOverhead <- createPressure.foreverM.background.use(_ => measure)
      _ <- IO.println(s"Loaded overhead: $loadedOverhead\n")

      _ <- IO.println("Measuring unloaded 100x...")
      unloaded100Overhead <- List.fill(100)(measure).parSequence
      _ <- IO.println(s"Unloaded overhead 100x: ${unloaded100Overhead.max}\n")

      _ <- IO.println("Measuring heavily loaded 100x...")
      loaded100Overhead <- createPressure
        .foreverM
        .background
        .use(_ => List.fill(100)(measure).parSequence)
      _ <- IO.println(s"Loaded overhead: ${loaded100Overhead.max}\n")
    } yield ()
}
