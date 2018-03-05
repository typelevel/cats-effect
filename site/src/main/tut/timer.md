---
layout: docs
title:  "Timer"
number: 8
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
