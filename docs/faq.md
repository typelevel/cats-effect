---
id: faq
title: FAQ
---

## Scala CLI

[Scala CLI](https://scala-cli.virtuslab.org/) can run both `.sc` files and `.scala` files. `.sc` files allow definitions at the top level and a main method is synthesized to run it. Unfortunately this does not work well with `IO#unsafeRunSync`. You should put your cats-effect code inside the `run` method of an `IOApp` and save it as a `.scala` file instead.

```scala-cli
//> using scala "2.13.8"
//> using lib "org.typelevel::cats-effect::3.4.5"

import cats.effect._

object HelloWorld extends IOApp.Simple {
  val run: IO[Unit] = IO.println("Hello world")
}
```

```sh
$ scala-cli Hello.scala
Hello world
```

### Native Image Example

[Scala CLI](https://scala-cli.virtuslab.org/) can be leveraged to produce a native-image executable using the [package command](https://scala-cli.virtuslab.org/docs/commands/package#native-image):

```sh
$ scala-cli package --native-image --graalvm-version 22.1.0 -f Hello.scala -- --no-fallback
[...]
$ ./HelloWorld
Hello world
```

> Note: GraalVm Native Image > 21.0.0 and `--no-fallback` are mandatory: see [here](core/native-image.md) for details

### Scala Native Example

[Scala CLI](https://scala-cli.virtuslab.org/) can be leveraged to produce a [Scala Native](https://github.com/scala-native/scala-native) executable using the [package command](https://scala-cli.virtuslab.org/docs/commands/package/#scala-native):

```sh
$ scala-cli package --native Hello.scala
[...]
$ ./HelloWorld
Hello world
```

See [here](core/scala-native.md) for details.

## Why is my `IO(...)` running on a blocking thread?

Cats Effect guarantees that `IO.blocking(...)` will run on a blocking thread. However, in many situations it may also run a non-blocking operation `IO(...)` (or `IO.delay(...)`) on a blocking thread. Do not be alarmed! This is an optimization.

Consider the following program:

```scala
IO.blocking(...) *> IO(...) *> IO.blocking(...)
```

If the `IO(...)` in the middle was run on the compute pool this would require:

1. recquisitioning a blocking thread, and running the first blocking op
2. shifting back to the compute pool, for the non-blocking op
3. recquisitioning another blocking thread, for the second blocking op

So in this case, the intermediate `IO(...)` is highly likely to run on a blocking thread. This enables the entire sequence to run on the same blocking thread, without any shifting.

## Dealing with Starvation

Cats Effect 3.4.0 introduced a default-enabled *starvation checker*, which produces warnings like the following:

```
[WARNING] Your CPU is probably starving. Consider increasing the granularity
of your delays or adding more cedes. This may also be a sign that you are
unintentionally running blocking I/O operations (such as File or InetAddress)
without the blocking combinator.
```

If you're seeing this warning and have not changed any of the default configurations, it means that your application is taking at least *100 milliseconds* to respond to an external asynchronous event. In this case, the runtime is measuring this fact using a timer, but external events are also things such as new connections, request bodies, upstream responses, and such. In other words, **if you're seeing this warning, it means your response latencies are *at least* this long.**

- If this level of application performance is not within acceptable bounds, please see the [starvation and tuning](core/starvation-and-tuning.md) documentation for more discussion on how you can resolve the issue
- If 100 milliseconds is acceptable for your use-case, but you would still like to preserve checking with some higher time-bound, you can adjust this by overriding the `cpuStarvationCheckInterval` (default: `1.second`) in `IORuntimeConfig`. The threshold coefficient itself (default: `0.1d`) is configurable via `cpuStarvationCheckThreshold`, but it is generally best practice to tune the interval rather than the threshold. Increasing the interval increases the absolute threshold proportionally (e.g. setting the interval to `2.seconds` would change the warning cutoff to 200 milliseconds)
- If you would like to entirely disable this check, you can do so by overriding the `cpuStarvationCheckInitialDelay` value within `IORuntimeConfig` to `Duration.Inf`

Please understand that this warning is essentially never a false negative. Outside of some extremely rare circumstances, it accurately measures the responsiveness of your application runtime. However, as a corollary of this accuracy, it is measuring not only your application runtime, but also the system (and other adjacent processes on that system) on which your application is running. Thus, if you see this warning, it essentially always means that either the checker's definition of "responsiveness" (100 millisecond SLA) is different from what you expected, or it means that there is some legitimate problem *somewhere* in your application, your JVM, your kernel, your host environment, or all of the above.
