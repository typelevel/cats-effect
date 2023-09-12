package cats.effect.metrics

import scala.concurrent.duration.FiniteDuration

final case class CpuStarvationWarningMetrics(
    occurrenceTime: FiniteDuration,
    clockDrift: FiniteDuration,
    starvationThreshold: Double,
    starvationInterval: FiniteDuration)
