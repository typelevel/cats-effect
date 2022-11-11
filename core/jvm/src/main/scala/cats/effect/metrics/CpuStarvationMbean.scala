package cats.effect.metrics

/**
 * An MBean interfaces for monitoring when CPU starvation occurs.
 */
private[metrics] trait CpuStarvationMbean {

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
}
