package cats.effect.metrics

import java.io.{PrintWriter, StringWriter}
import java.lang.management.ManagementFactory

import cats.effect.std.Console
import cats.effect.{IO, Resource}
import javax.management.{MBeanServer, ObjectName}

import scala.concurrent.duration.FiniteDuration

private[effect] class JvmCpuStarvationMetrics private (mbean: CpuStarvationMbeanImpl)
    extends CpuStarvationMetrics {
  override def incCpuStarvationCount: IO[Unit] = mbean.incStarvationCount

  override def recordClockDrift(drift: FiniteDuration): IO[Unit] = mbean.recordDrift(drift)
}

object JvmCpuStarvationMetrics {
  private[this] val mBeanObjectName = new ObjectName("cats.effect.metrics:type=CpuStarvation")

  private[this] def warning(th: Throwable) = {
    val exceptionWriter = new StringWriter()
    th.printStackTrace(new PrintWriter(exceptionWriter))

    s"""[WARNING] Failed to register cats-effect CPU starvation MBean, proceeding with
       |no-operation versions. You will not see MBean metrics for CPU starvation.
       |Exception follows: \n ${exceptionWriter.toString}
       |""".stripMargin
  }

  private[effect] def apply(): Resource[IO, CpuStarvationMetrics] = {
    val acquire: IO[(MBeanServer, JvmCpuStarvationMetrics)] = for {
      mBeanServer <- IO.delay(ManagementFactory.getPlatformMBeanServer)
      mBean <- CpuStarvationMbeanImpl()
      _ <- IO.blocking(mBeanServer.registerMBean(mBean, mBeanObjectName))
    } yield (mBeanServer, new JvmCpuStarvationMetrics(mBean))

    Resource
      .make(acquire) {
        case (mbeanServer, _) => IO.blocking(mbeanServer.unregisterMBean(mBeanObjectName))
      }
      .map(_._2)
      .handleErrorWith[CpuStarvationMetrics, Throwable] { th =>
        Resource.eval(Console[IO].errorln(warning(th))).map(_ => CpuStarvationMetrics.noOp)
      }
  }
}
