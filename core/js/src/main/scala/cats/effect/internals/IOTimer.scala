/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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
package internals

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.scalajs.js.timers

/**
 * Internal API — JavaScript specific implementation for a [[Timer]]
 * powered by `IO`.
 *
 * Deferring to JavaScript's own `setTimeout` for `sleep`.
 */
final private[internals] class IOTimer(ec: ExecutionContext) extends Timer[IO] {
  import IOTimer.{clearTimeout, setTimeout, ScheduledTick}
  val clock: Clock[IO] = Clock.create[IO]

  def sleep(timespan: FiniteDuration): IO[Unit] =
    IO.Async(new IOForkedStart[Unit] {
      def apply(conn: IOConnection, cb: Either[Throwable, Unit] => Unit): Unit = {
        val task = setTimeout(timespan.toMillis, ec, new ScheduledTick(conn, cb))
        // On the JVM this would need a ForwardCancelable,
        // but not on top of JS as we don't have concurrency
        conn.push(IO(clearTimeout(task)))
      }
    })
}

/**
 * Internal API
 */
private[internals] object IOTimer {

  /**
   * Globally available implementation.
   */
  val global: Timer[IO] = new IOTimer(new ExecutionContext {
    def execute(r: Runnable): Unit =
      try {
        r.run()
      } catch { case e: Throwable => e.printStackTrace() }

    def reportFailure(e: Throwable): Unit =
      e.printStackTrace()
  })

  private def setTimeout(delayMillis: Long, ec: ExecutionContext, r: Runnable): timers.SetTimeoutHandle =
    timers.setTimeout(delayMillis.toDouble)(ec.execute(r))

  private def clearTimeout(handle: timers.SetTimeoutHandle): Unit =
    timers.clearTimeout(handle)

  final private class ScheduledTick(conn: IOConnection, cb: Either[Throwable, Unit] => Unit) extends Runnable {
    def run(): Unit = {
      conn.pop()
      cb(Callback.rightUnit)
    }
  }
}
