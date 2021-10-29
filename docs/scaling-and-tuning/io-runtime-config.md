---
id: io-runtime-configuration
title: `IORuntime` Configuration
---
# `IORuntime` Configuration

It is possible to configure your `IORuntime` externally, at runtime.

## JVM
You can set these as system properties via JVM arguments.
```
-Dcats.effect.propName=propValue
```

## JS
On Node.js, these can be set via ordinary environment variables.
For browsers (and generally any other runtime) you can instead define globals in your JavaScript bundle:

```javascript
process.env.CATS_EFFECT_PROPNAME = propValue;
// alias for compatibility with Create React App
process.env.REACT_APP_CATS_EFFECT_PROPNAME = propValue;
```

This can be done for example with the [EnvironmentPlugin for Webpack](https://webpack.js.org/plugins/environment-plugin/) or [Create React App](https://create-react-app.dev/docs/adding-custom-environment-variables/).

| System Property (JVM) / ENV Variable (JS)                                                         | Value (default)    | Description                                                                                                                       |
|---------------------------------------------------------------------------------------------------|--------------------|-----------------------------------------------------------------------------------------------------------------------------------|
| `cats.effect.logNonDaemonThreadsOnExit` <br/> N/A                                                 | `Boolean` (`true`) | Whether or not we should check for non-daemon threads on JVM exit.                                                                |
| `cats.effect.logNonDaemonThreads.sleepIntervalMillis` <br/> N/A                                   | `Long` (`10000L`)  | Time to sleep between checking for presence of non-daemon threads.                                                                |
| `cats.effect.cancelation.check.threshold ` <br/> `CATS_EFFECT_CANCELATION_CHECK_THRESHOLD`        | `Int` (`512`)      | Configure how often cancellation is checked. By default, every 512 iterations of the run loop.                                    |
| `cats.effect.auto.yield.threshold.multiplier` <br/> `CATS_EFFECT_AUTO_YIELD_THRESHOLD_MULTIPLIER` | `Int` (`2`)        | `autoYieldThreshold = autoYieldThresholdMultiplier x cancelationCheckThreshold`. See [thread-model](../thread-model.md).          |
| `cats.effect.tracing.exceptions.enhanced` br/> `CATS_EFFECT_TRACING_EXCEPTIONS_ENHANCED`          | `Boolean` (`true`) | Augment the stack traces of caught exceptions to include frames from the asynchronous stack traces. See [tracing](../tracing.md). |
| `cats.effect.tracing.buffer.size` <br/> `CATS_EFFECT_TRACING_BUFFER_SIZE`                         | `Int` (`16`)       | Initial tracing buffer size is 2 by the power of this value. Thus, 2^16 by default.                                               |
| `cats.effect.shutdown.hook.timeout` <br/> `CATS_EFFECT_SHUTDOWN_HOOK_TIMEOUT`                     | `Duration` (`Inf`) | If your `IOApp` encounters a `Ctrl+C` or `System.exit`, how long it should wait for fiber cancellation before forcibly stopping.  |
