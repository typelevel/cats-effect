package cats.effect;

final class TracingConstants {

    /**
     * Sets stack tracing mode for a JVM process, which controls
     * how much stack trace information is captured.
     * Acceptable values are: NONE, CACHED, FULL.
     */
    private static final String stackTracingMode = Optional.ofNullable(System.getProperty("cats.effect.stackTracingMode"))
            .filter(x -> !x.isEmpty())
            .orElse("cached");

    public static final boolean isCachedStackTracing = stackTracingMode.equalsIgnoreCase("cached");

    public static final boolean isFullStackTracing = stackTracingMode.equalsIgnoreCase("full");

    public static final boolean isStackTracing = isFullStackTracing || isCachedStackTracing;

    /**
     * The number of trace lines to retain during tracing. If more trace
     * lines are produced, then the oldest trace lines will be discarded.
     * Automatically rounded up to the nearest power of 2.
     */
    public static final int traceBufferLogSize = Optional.ofNullable(System.getProperty("cats.effect.traceBufferLogSize"))
        .filter(x -> !x.isEmpty())
        .flatMap(x -> {
            try {
                return Optional.of(Integer.valueOf(x));
            } catch (Exception e) {
                return Optional.empty();
            }
        })
        .orElse(4);

    /**
     * Sets the enhanced exceptions flag, which controls whether or not the
     * stack traces of IO exceptions are augmented to include async stack trace information.
     * Stack tracing must be enabled in order to use this feature.
     * This flag is enabled by default.
     */
    public static final boolean enhancedExceptions = Optional.ofNullable(System.getProperty("cats.effect.enhancedExceptions"))
            .map(x -> Boolean.valueOf(x))
            .orElse(true);

}
