---
layout: docsplus
title:  "Timer"
number: 11
source: "shared/src/main/scala/cats/effect/Timer.scala"
scaladoc: "#cats.effect.Timer"
---

It is a scheduler of tasks. You can think of it as the purely functional equivalent of:

- Java's [ScheduledExecutorService](https://docs.oracle.com/javase/9/docs/api/java/util/concurrent/ScheduledExecutorService.html).
- JavaScript's [setTimeout](https://developer.mozilla.org/en-US/docs/Web/API/WindowOrWorkerGlobalScope/setTimeout setTimeout).

It provides:

- The ability to get the current time.
- Ability to delay the execution of a task with a specified time duration.

It does all of that in an `F[_]` monadic context that can suspend side effects and is capable of asynchronous execution (e.g. `IO`).

This is NOT a typeclass, as it does not have the coherence requirement.

```scala mdoc:silent
import cats.effect.Clock
import scala.concurrent.duration.FiniteDuration

trait Timer[F[_]] {
  def clock: Clock[F]
  def sleep(duration: FiniteDuration): F[Unit]
}
```

As mentioned in the `IO` documentation, there's a default instance of `Timer[IO]` available. However, you might want to implement your own to have a fine-grained control over your thread pools. You can look at the mentioned implementation for more details, but it roughly looks like this:

```scala mdoc:reset:silent
import java.util.concurrent.ScheduledExecutorService

import cats.effect.{IO, Timer, Clock}
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

final class MyTimer(ec: ExecutionContext, sc: ScheduledExecutorService) extends Timer[IO] {
  override val clock: Clock[IO] =
    new Clock[IO] {
      override def realTime(unit: TimeUnit): IO[Long] =
        IO(unit.convert(System.currentTimeMillis(), MILLISECONDS))

      override def monotonic(unit: TimeUnit): IO[Long] =
        IO(unit.convert(System.nanoTime(), NANOSECONDS))
    }

  override def sleep(timespan: FiniteDuration): IO[Unit] =
    IO.cancelable { cb =>
      val tick = new Runnable {
        def run() = ec.execute(new Runnable {
          def run() = cb(Right(()))
        })
      }
      val f = sc.schedule(tick, timespan.length, timespan.unit)
      IO(f.cancel(false)).void
    }
}
```

## Configuring the global Scheduler

The one-argument overload of `IO.timer` lazily instantiates a global `ScheduledExecutorService`, which is never shut down.  This is fine for most applications, but leaks threads when the class is repeatedly loaded in the same JVM, as is common in testing. The global scheduler can be configured with the following system properties:

* `cats.effect.global_scheduler.threads.core_pool_size`: sets the core pool size of the global scheduler. Defaults to `2`.
* `cats.effect.global_scheduler.keep_alive_time_ms`: allows the global scheduler's core threads to timeout and terminate when idle. `0` keeps the threads from timing out. Defaults to `0`. Value is in milliseconds.

These properties only apply on the JVM.

Also see these related data types:

- [Clock](./clock.md): for time measurements and getting the current clock
- [ContextShift](./contextshift.md): the pure equivalent of an `ExecutionContext`
