package cats.effect

object TracingConstants {
  final val isCachedStackTracing: Boolean = false

  final val isFullStackTracing: Boolean = false

  final val isStackTracing: Boolean = isFullStackTracing || isCachedStackTracing

  final val traceBufferLogSize: Int = 4

  final val enhancedExceptions: Boolean = false
}
