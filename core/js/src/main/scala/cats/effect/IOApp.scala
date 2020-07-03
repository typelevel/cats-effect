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

import scala.concurrent.duration.FiniteDuration
import scala.scalajs.concurrent.JSExecutionContext
import scala.scalajs.js.timers

trait IOApp {

  def run(args: List[String]): IO[Int]

  final def main(args: Array[String]): Unit = {
    val context = JSExecutionContext.queue

    val timer = new UnsafeTimer {
      def sleep(delay: FiniteDuration, task: Runnable): Runnable = {
        val handle = timers.setTimeout(delay)(task.run())
        () => timers.clearTimeout(handle)
      }

      def nowMillis() = System.currentTimeMillis()
      def monotonicNanos() = System.nanoTime()
    }

    run(args.toList).unsafeRunAsync(context, timer) {
      case Left(t) => throw t
      case Right(code) => System.exit(code)
    }
  }
}
