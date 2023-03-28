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

package cats.effect.unsafe.metrics

import cats.effect.{IO, Resource}
import cats.effect.std.Console
import cats.syntax.functor._

import java.io.{PrintWriter, StringWriter}
import java.lang.management.ManagementFactory

import javax.management.{MBeanServer, ObjectName, StandardMBean}

/**
 * An MBean interfaces for monitoring when CPU starvation occurs.
 */
private[effect] trait CpuStarvationSamplerMBean {

  /**
   * Returns the number of times CPU starvation has occurred.
   *
   * @return
   *   count of the number of times CPU starvation has occurred.
   */
  def getCpuStarvationCount(): Long

  /**
   * Tracks the maximum clock seen during runtime.
   *
   * @return
   *   the current maximum clock drift observed in milliseconds.
   */
  def getMaxClockDriftMs(): Long

  /**
   * Tracks the current clock drift.
   *
   * @return
   *   the current clock drift in milliseconds.
   */
  def getCurrentClockDriftMs(): Long
}

private[effect] object CpuStarvationSamplerMBean {

  private[this] val mBeanObjectName = new ObjectName("cats.effect.metrics:type=CpuStarvation")

  private[this] def warning(th: Throwable) = {
    val exceptionWriter = new StringWriter()
    th.printStackTrace(new PrintWriter(exceptionWriter))

    s"""[WARNING] Failed to register Cats Effect CPU starvation MBean, proceeding with
       |no-operation versions. You will not see MBean metrics for CPU starvation.
       |Exception follows: \n ${exceptionWriter.toString}
       |""".stripMargin
  }

  private[effect] def register(sampler: CpuStarvationSampler): Resource[IO, Unit] = {
    val acquire: IO[MBeanServer] =
      for {
        mBeanServer <- IO.delay(ManagementFactory.getPlatformMBeanServer)
        mBean <- IO.pure(new StandardMBean(sampler, classOf[CpuStarvationSamplerMBean]))
        // To allow user-defined program to use the compute pool from the beginning,
        // here we use `IO.delay` rather than `IO.blocking`.
        _ <- IO.delay(mBeanServer.registerMBean(mBean, mBeanObjectName))
      } yield mBeanServer

    def release(server: MBeanServer): IO[Unit] =
      IO.blocking(server.unregisterMBean(mBeanObjectName))

    Resource
      .make(acquire)(release)
      .void
      .handleErrorWith[Unit, Throwable](th => Resource.eval(Console[IO].error(warning(th))))
  }

}
