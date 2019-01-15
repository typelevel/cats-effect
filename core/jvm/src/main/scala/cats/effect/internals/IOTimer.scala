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

import java.util.concurrent.{Executors, ScheduledExecutorService, ThreadFactory}
import cats.effect.internals.Callback.T
import cats.effect.internals.IOShift.Tick
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
 * Internal API — JVM specific implementation of a `Timer[IO]`.
 *
 * Depends on having a Scala `ExecutionContext` for the
 * execution of tasks after their schedule (i.e. bind continuations) and on a Java
 * `ScheduledExecutorService` for scheduling ticks with a delay.
 */
private[internals] final class IOTimer private (
  ec: ExecutionContext, sc: ScheduledExecutorService)
  extends Timer[IO] {

  import IOTimer._

  val clock : Clock[IO] = Clock.create[IO]

  override def sleep(timespan: FiniteDuration): IO[Unit] =
    IO.Async(new IOForkedStart[Unit] {
      def apply(conn: IOConnection, cb: T[Unit]): Unit = {
        // Doing what IO.cancelable does
        val ref = ForwardCancelable()
        conn.push(ref.cancel)
        // Race condition test
        if (!conn.isCanceled) {
          val f = sc.schedule(new ShiftTick(conn, cb, ec), timespan.length, timespan.unit)
          ref.complete(IO(f.cancel(false)))
        } else {
          ref.complete(IO.unit)
        }
      }
    })
}

private[internals] object IOTimer {
  /** Builder. */
  def apply(ec: ExecutionContext): Timer[IO] =
    apply(ec, scheduler)

  /** Builder. */
  def apply(ec: ExecutionContext, sc: ScheduledExecutorService): Timer[IO] =
    new IOTimer(ec, sc)

  /** Global instance, used by `IOApp`. */
  lazy val global: Timer[IO] =
    apply(ExecutionContext.Implicits.global)

  private[internals] lazy val scheduler: ScheduledExecutorService =
    Executors.newScheduledThreadPool(2, new ThreadFactory {
      def newThread(r: Runnable): Thread = {
        val th = new Thread(r)
        th.setName(s"cats-effect-scheduler-${th.getId}")
        th.setDaemon(true)
        th
      }
    })

  private final class ShiftTick(
    conn: IOConnection,
    cb: Either[Throwable, Unit] => Unit,
    ec: ExecutionContext)
    extends Runnable {

    def run(): Unit = {
      // Shifts actual execution on our `ExecutionContext`, because
      // the scheduler is in charge only of ticks and the execution
      // needs to shift because the tick might continue with whatever
      // bind continuation is linked to it, keeping the current thread
      // occupied
      conn.pop()
      ec.execute(new Tick(cb))
    }
  }
}
