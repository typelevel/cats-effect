---
id: faq
title: FAQ
---

## Scala CLI

[Scala CLI](https://scala-cli.virtuslab.org/) can run both `.sc` files and `.scala` files. `.sc` files allow definitions at the top level and a main method is synthesized to run it. Unfortunately this does not work well with `IO#unsafeRunSync`. You should put your cats-effect code inside the `run` method of an `IOApp` and save it as a `.scala` file instead.

```scala-cli
//> using scala "2.13.8"
//> using lib "org.typelevel::cats-effect::3.4.8"

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

1. requisitioning a blocking thread, and running the first blocking op
2. shifting back to the compute pool, for the non-blocking op
3. requisitioning another blocking thread, for the second blocking op

So in this case, the intermediate `IO(...)` is highly likely to run on a blocking thread. This enables the entire sequence to run on the same blocking thread, without any shifting. As soon as any asynchronous operation (or an auto-cede boundary) is encountered, the fiber will be shifted back to a compute thread. The tradeoff here is to create a small amount of unnecessary thread contention in exchange for avoiding unnecessary context shifts in many cases.

Note that this is just a specific example to demonstrate why running an non-blocking `IO(...)` on a blocking thread can be beneficial, but it is not the only situation in which you may observe this behavior.

## Dealing with Starvation

Cats Effect 3.4.0 introduced a default-enabled *starvation checker*, which produces warnings like the following:

```
2023-01-28T00:16:24.101Z [WARNING] Your app's responsiveness to a new asynchronous 
event (such as a new connection, an upstream response, or a timer) was in excess
of 40 milliseconds. Your CPU is probably starving. Consider increasing the 
granularity of your delays or adding more cedes. This may also be a sign that you
are unintentionally running blocking I/O operations (such as File or InetAddress)
without the blocking combinator.
```

If you're seeing this warning and have not changed any of the default configurations, it means that your application is taking at least *100 milliseconds* to respond to an external asynchronous event. In this case, the runtime is measuring this fact using a timer, but external events are also things such as new connections, request bodies, upstream responses, and such. In other words, **if you're seeing this warning, it means your response latencies are *at least* this long.**

- If this level of application performance is not within acceptable bounds, please see the [starvation and tuning](core/starvation-and-tuning.md) documentation for more discussion on how you can resolve the issue
- If 100 milliseconds is acceptable for your use-case, but you would still like to preserve checking with some higher time-bound, you can adjust this by overriding the `cpuStarvationCheckInterval` (default: `1.second`) in `IORuntimeConfig`. The threshold coefficient itself (default: `0.1d`) is configurable via `cpuStarvationCheckThreshold`, but it is generally best practice to tune the interval rather than the threshold. Increasing the interval increases the absolute threshold proportionally (e.g. setting the interval to `2.seconds` would change the warning cutoff to 200 milliseconds)
- If you would like to entirely disable this check, you can do so by overriding the `cpuStarvationCheckInitialDelay` value within `IORuntimeConfig` to `Duration.Inf`

Please understand that this warning is essentially never a false negative. Outside of some extremely rare circumstances, it accurately measures the responsiveness of your application runtime. However, as a corollary of this accuracy, it is measuring not only your application runtime, but also the system (and other adjacent processes on that system) on which your application is running. Thus, if you see this warning, it essentially always means that either the checker's definition of "responsiveness" (100 millisecond SLA) is different from what you expected, or it means that there is some legitimate problem *somewhere* in your application, your JVM, your kernel, your host environment, or all of the above.

## How do I cancel or timeout `delay` or `blocking`?

Under normal circumstances, effects constructed with `delay` or `blocking` are *uncancelable*, meaning that they will suppress any attempt to `cancel` their fiber until the effect completes. A classic example:

```scala
IO.blocking(Thread.sleep(1000))
```

The above will block a thread for 1 second. This is done in a *relatively* safe fashion since the `blocking` constructor will ensure that the compute pool is not starved for threads, but the behavior can still be unintuitive. For example:

```scala
IO.blocking(Thread.sleep(1000)).timeout(100.millis)
```

This effect will take the full 1 second to complete! This is because the timeout fires at the 100 millisecond mark, but since `blocking` is uncancelable, `IO` will wait for it to complete. This is very much by design since it avoids situations in which resources might leak or be left in an invalid state due to unsupported cancelation, but it can be confusing in scenarios like this.

There are two possible ways to address this situation, and the correct one to use depends on a number of different factors. In this particular scenario, `Thread.sleep` *happens* to correctly respect Java `Thread` interruption, and so we can fix this by swapping `blocking` for `interruptible`:

```scala
IO.interuptible(Thread.sleep(1000)).timeout(100.millis)
```

The above will return in 100 milliseconds, raising a `TimeoutException` as expected.

However, not *all* effects respect thread interruption. Notably, most things involving `java.io` file operations (e.g. `FileReader`) ignore interruption. When working with this type of effect, we must turn to other means. A simple and naive example:

```scala
def readBytes(fis: FileInputStream) = 
  IO blocking {
    var bytes = new ArrayBuffer[Int]

    var i = fis.read()
    while (i >= 0) {
      bytes += i
      i = fis.read()
    }

    bytes.toArray
  }

IO.bracket(
  IO(new FileInputStream(inputFile)))(
  readBytes(_))(
  fis => IO(fis.close()))
```

In the above snippet, swapping `blocking` for `interruptible` won't actually help, since `fis.read()` ignores `Thread` interruption! However, we can still *partially* resolve this by introducing a bit of external state:

```scala
IO(new AtomicBoolean(false)) flatMap { flag =>
  def readBytes(fis: FileInputStream) = 
    IO blocking {
      var bytes = new ArrayBuffer[Int]

      var i = fis.read()
      while (i >= 0 && !flag.get()) {
        bytes += i
        i = fis.read()
      }

      bytes.toArray
    }

  val readAll = IO.bracket(
    IO(new FileInputStream(inputFile)))(
    readBytes(_))(
    fis => IO(fis.close()))

  readAll.cancelable(IO(flag.set(true)))
}
```

This is *almost* the same as the previous snippet except for the introduction of an `AtomicBoolean` which is checked on each iteration of the `while` loop. We then set this flag by using `cancelable` (right at the end), which makes the whole thing (safely!) cancelable, despite the fact that it was defined with `blocking` (note this also works with `delay` and absolutely everything else).

It is still worth keeping in mind that this is only a partial solution. Whenever `fis.read()` is blocked, cancelation will wait for that blocking to complete regardless of how long it takes. In other words, we still can't interrupt the `read()` function any more than we could with `interruptible`. All that `cancelable` is achieving here is allowing us to encode a more bespoke cancelation protocol for a particular effect, in this case in terms of `AtomicBoolean`.

> Note: It is actually possible to implement a *proper* solution for cancelation here simply by applying the `cancelable` to the inner `blocking` effect and defining the handler to be `IO(fis.close())`, in addition to adding some error handling logic to catch the corresponding exception. This is a special case however, specific to `FileInputStream`, and doesn't make for as nice of an example. :-)

The reason this is safe is it effectively leans entirely on *cooperative* cancelation. It's relatively common to have effects which cannot be canceled by normal means (and thus are, correctly, `uncancelable`) but which *can* be terminated early by using some ancillary protocol (in this case, an `AtomicBoolean`). Note that nothing is magic here and this is still fully safe with respect to backpressure and other finalizers. For example, `fis.close()` will still be run at the proper time, and cancelation of this fiber will only complete when all finalizers are done, exactly the same as non-`uncancelable` effects.
