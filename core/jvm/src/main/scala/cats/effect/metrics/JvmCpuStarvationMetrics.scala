/*
 * Copyright 2020-2024 Typelevel
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

package cats.effect.metrics

import cats.effect.{IO, Resource}
import cats.effect.std.Console

import scala.concurrent.duration.FiniteDuration

import java.io.{PrintWriter, StringWriter}
import java.lang.management.ManagementFactory

import javax.management.{MBeanServer, ObjectName}

private[effect] class JvmCpuStarvationMetrics private (mbean: CpuStarvation)
    extends CpuStarvationMetrics {
  override def incCpuStarvationCount: IO[Unit] = mbean.incStarvationCount

  override def recordClockDrift(drift: FiniteDuration): IO[Unit] = mbean.recordDrift(drift)
}

private[effect] object JvmCpuStarvationMetrics {
  private[this] val mBeanObjectName = new ObjectName("cats.effect.metrics:type=CpuStarvation")

  private[this] def warning(th: Throwable) = {
    val exceptionWriter = new StringWriter()
    th.printStackTrace(new PrintWriter(exceptionWriter))

    s"""[WARNING] Failed to register Cats Effect CPU starvation MBean, proceeding with
       |no-operation versions. You will not see MBean metrics for CPU starvation.
       |Exception follows: \n ${exceptionWriter.toString}
       |""".stripMargin
  }

  private[this] class NoOpCpuStarvationMetrics extends CpuStarvationMetrics {
    override def incCpuStarvationCount: IO[Unit] = IO.unit

    override def recordClockDrift(drift: FiniteDuration): IO[Unit] = IO.unit
  }

  private[effect] def apply(): Resource[IO, CpuStarvationMetrics] = {
    val acquire: IO[(MBeanServer, JvmCpuStarvationMetrics)] = for {
      mBeanServer <- IO.delay(ManagementFactory.getPlatformMBeanServer)
      mBean <- CpuStarvation()
      // To allow user-defined program to use the compute pool from the beginning,
      // here we use `IO.delay` rather than `IO.blocking`.
      _ <- IO.delay(mBeanServer.registerMBean(mBean, mBeanObjectName))
    } yield (mBeanServer, new JvmCpuStarvationMetrics(mBean))

    Resource
      .make(acquire) {
        case (mbeanServer, _) => IO.blocking(mbeanServer.unregisterMBean(mBeanObjectName))
      }
      .map(_._2)
      .handleErrorWith[CpuStarvationMetrics, Throwable] { th =>
        Resource.eval(Console[IO].errorln(warning(th))).map(_ => new NoOpCpuStarvationMetrics)
      }
  }
}
