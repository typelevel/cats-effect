---
id: system-properties
title: System Properties
---
# System properties

There are some properties configurable from system properties.

## JVM
You can set these properties via JVM arguments.
```
-Dcats.effect.propName=propValue
```

|Property|Value(default)|Description|CE version |
|---|---|---|---|
|cats.effect.logNonDaemonThreadsOnExit| Boolean(true)| Whether or not we should check for non-daemon threads on jvm exit. |3|
|cats.effect.logNonDaemonThreads.sleepIntervalMillis|Long(10000L)|Time to sleep between checking for non-daemon threads present|3|
|cats.effect.cancelation.check.threshold|Int(512)|configure how often cancellation is checked. By default, Every 512 iteration of the run loop.|3|
|cats.effect.auto.yield.threshold.multiplier|Int(2)|This property determinses auto-yield threshold in combination with cancellation check threshold. Auto-yield threshold is product of them. About auto-yielding, see [thread-model](../thread-model.md).|3|
|cats.effect.tracing.exceptions.enhanced|Boolean(true)|Augment the stack traces of caught exceptions to include frames from the asynchronous stack traces. For further information, visit [tracing](../tracing.md) page|2, 3|
|cats.effect.tracing.buffer.size|Int(16)|Initial tracing buffer size is 2 by the power of this value. Thus, 2^16 by default.|2, 3|

## JS
You can set these properties via environment variables.

|Property|Value(default)|Description|CE version|
|---|---|---|---|
|CATS_EFFECT_TRACING_MODE|"cached"\|"full"("cached")|Configure tracing mode.|3|
|REACT_APP_CATS_EFFECT_TRACING_MODE|"cached"\|"full"("cached")|Alias for CATS_EFFECT_TRACING_MODE in react app|3|
