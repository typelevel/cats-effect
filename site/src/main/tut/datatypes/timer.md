---
layout: docs
title:  "Timer"
number: 11
source: "shared/src/main/scala/cats/effect/Timer.scala"
scaladoc: "#cats.effect.Timer"
---

# Timer

It is a scheduler of tasks. You can think of it as the purely functional equivalent of:

- Java's [ScheduledExecutorService](https://docs.oracle.com/javase/9/docs/api/java/util/concurrent/ScheduledExecutorService.html).
- JavaScript's [setTimeout](https://developer.mozilla.org/en-US/docs/Web/API/WindowOrWorkerGlobalScope/setTimeout setTimeout).

It provides:

- The ability to get the current time.
- Thread / call-stack shifting.
- Ability to delay the execution of a task with a specified time duration.

It does all of that in an `F[_]` monadic context that can suspend side effects and is capable of asynchronous execution (e.g. `IO`).

This is NOT a typeclass, as it does not have the coherence requirement.

```tut:book:silent
import scala.concurrent.duration.{FiniteDuration, TimeUnit}

trait Timer[F[_]] {
  def clockRealTime(unit: TimeUnit): F[Long]
  def clockMonotonic(unit: TimeUnit): F[Long]
  def sleep(duration: FiniteDuration): F[Unit]
  def shift: F[Unit]
}
```

As mentioned in the `IO` documentation, there's a default instance of `Timer[IO]` available. However, you might want to implement your own to have a fine-grained control over your thread pools. You can look at the mentioned implementation for more details, but it roughly looks like this:

```tut:book
import java.util.concurrent.ScheduledExecutorService

import cats.effect.{IO, Timer}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

final class MyTimer(ec: ExecutionContext, sc: ScheduledExecutorService) extends Timer[IO] {

  override def clockRealTime(unit: TimeUnit): IO[Long] =
    IO(unit.convert(System.currentTimeMillis(), MILLISECONDS))

  override def clockMonotonic(unit: TimeUnit): IO[Long] =
    IO(unit.convert(System.nanoTime(), NANOSECONDS))

  override def sleep(timespan: FiniteDuration): IO[Unit] =
    IO.cancelable { cb =>
      val tick = new Runnable {
        def run() = ec.execute(new Runnable {
          def run() = cb(Right(()))
        })
      }
      val f = sc.schedule(tick, timespan.length, timespan.unit)
      IO(f.cancel(false))
    }

  override def shift: IO[Unit] =
    IO.async(cb => ec.execute(new Runnable {
      def run() = cb(Right(()))
    }))

}
```
