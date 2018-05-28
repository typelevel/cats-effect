/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS, NANOSECONDS, TimeUnit}

/**
 * Internal API — JVM specific implementation of a `Timer[IO]`.
 *
 * Depends on having a Scala `ExecutionContext` for the actual
 * execution of tasks (i.e. bind continuations) and on a Java
 * `ScheduledExecutorService` for scheduling ticks with a delay.
 */
private[internals] final class IOTimer private (
  ec: ExecutionContext, sc: ScheduledExecutorService)
  extends Timer[IO] {

  import IOTimer._

  override def clockRealTime(unit: TimeUnit): IO[Long] =
    IO(unit.convert(System.currentTimeMillis(), MILLISECONDS))

  override def clockMonotonic(unit: TimeUnit): IO[Long] =
    IO(unit.convert(System.nanoTime(), NANOSECONDS))

  override def sleep(timespan: FiniteDuration): IO[Unit] =
    IO.Async(new IOForkedStart[Unit] {
      def apply(conn: IOConnection, cb: Callback.T[Unit]): Unit = {
        val ref = ForwardCancelable()
        conn.push(ref)

        val f = sc.schedule(new ShiftTick(cb, ec), timespan.length, timespan.unit)
        ref := (() => f.cancel(false))
      }
    })

  override def shift: IO[Unit] =
    IO.Async(new IOForkedStart[Unit] {
      def apply(conn: IOConnection, cb: Callback.T[Unit]): Unit =
        ec.execute(new Tick(cb))
    })
}

private[internals] object IOTimer {
  /** Builder. */
  def apply(ec: ExecutionContext): Timer[IO] =
    apply(ec, scheduler)

  /** Builder. */
  def apply(ec: ExecutionContext, sc: ScheduledExecutorService): Timer[IO] =
    new IOTimer(ec, sc)

  private lazy val scheduler: ScheduledExecutorService =
    Executors.newScheduledThreadPool(2, new ThreadFactory {
      def newThread(r: Runnable): Thread = {
        val th = new Thread(r)
        th.setName("cats-effect")
        th.setDaemon(true)
        th
      }
    })

  private final class ShiftTick(
    cb: Either[Throwable, Unit] => Unit, ec: ExecutionContext)
    extends Runnable {
    def run() = {
      // Shifts actual execution on our `ExecutionContext`, because
      // the scheduler is in charge only of ticks and the execution
      // needs to shift because the tick might continue with whatever
      // bind continuation is linked to it, keeping the current thread
      // occupied
      ec.execute(new Tick(cb))
    }
  }

  private final class Tick(cb: Either[Throwable, Unit] => Unit)
    extends Runnable {
    def run() = cb(Callback.rightUnit)
  }
}