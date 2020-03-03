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

import java.util.concurrent.{ScheduledExecutorService, ScheduledThreadPoolExecutor, ThreadFactory, TimeUnit}
import cats.effect.internals.Callback.T
import cats.effect.internals.IOShift.Tick
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Try

/**
 * Internal API — JVM specific implementation of a `Timer[IO]`.
 *
 * Depends on having a Scala `ExecutionContext` for the
 * execution of tasks after their schedule (i.e. bind continuations) and on a Java
 * `ScheduledExecutorService` for scheduling ticks with a delay.
 */
final private[internals] class IOTimer private (ec: ExecutionContext, sc: ScheduledExecutorService) extends Timer[IO] {
  import IOTimer._

  val clock: Clock[IO] = Clock.create[IO]

  override def sleep(timespan: FiniteDuration): IO[Unit] =
    IO.Async(new IOForkedStart[Unit] {
      def apply(conn: IOConnection, cb: T[Unit]): Unit = {
        // Doing what IO.cancelable does
        val ref = ForwardCancelable()
        conn.push(ref.cancel)
        // Race condition test
        if (!conn.isCanceled) {
          val f = sc.schedule(new ShiftTick(conn, cb, ec), timespan.length, timespan.unit)
          ref.complete(IO {
            f.cancel(false)
            ()
          })
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

  private[internals] lazy val scheduler: ScheduledExecutorService =
    mkGlobalScheduler(sys.props)

  private[internals] def mkGlobalScheduler(props: collection.Map[String, String]): ScheduledThreadPoolExecutor = {
    val corePoolSize = props
      .get("cats.effect.global_scheduler.threads.core_pool_size")
      .flatMap(s => Try(s.toInt).toOption)
      .filter(_ > 0)
      .getOrElse(2)
    val keepAliveTime = props
      .get("cats.effect.global_scheduler.keep_alive_time_ms")
      .flatMap(s => Try(s.toLong).toOption)
      .filter(_ > 0L)

    val tp = new ScheduledThreadPoolExecutor(corePoolSize, new ThreadFactory {
      def newThread(r: Runnable): Thread = {
        val th = new Thread(r)
        th.setName(s"cats-effect-scheduler-${th.getId}")
        th.setDaemon(true)
        th
      }
    })
    keepAliveTime.foreach { timeout =>
      // Call in this order or it throws!
      tp.setKeepAliveTime(timeout, TimeUnit.MILLISECONDS)
      tp.allowCoreThreadTimeOut(true)
    }
    tp.setRemoveOnCancelPolicy(true)
    tp
  }

  final private class ShiftTick(
    conn: IOConnection,
    cb: Either[Throwable, Unit] => Unit,
    ec: ExecutionContext
  ) extends Runnable {
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
