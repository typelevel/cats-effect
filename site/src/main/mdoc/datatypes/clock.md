---
layout: docsplus
title:  "Clock"
number: 11
source: "shared/src/main/scala/cats/effect/Clock.scala"
scaladoc: "#cats.effect.Clock"
---

`Clock` provides the current time, as a pure alternative to:

- Java's [System.currentTimeMillis](https://docs.oracle.com/javase/8/docs/api/java/lang/System.html#currentTimeMillis--)
  for getting the "real-time clock" and
  [System.nanoTime](https://docs.oracle.com/javase/8/docs/api/java/lang/System.html#nanoTime--)
  for a monotonic clock useful for time measurements
- JavaScript's `Date.now()` and `performance.now()` 
  
The reason for providing this data type is two-fold:

- the exposed functions are pure, with the results suspended in `F[_]`,
  no reason to reinvent the wheel and write your own wrappers
- requiring this data type as a restriction means that code using
  `Clock` can have alternative implementations injected; for example
  time passing can be simulated in tests, such that time-based logic
  can be tested much more deterministically and with better performance,
  without actual delays happening
  
The interface looks like this:

```scala mdoc:silent
import scala.concurrent.duration.TimeUnit

trait Clock[F[_]] {

  def realTime(unit: TimeUnit): F[Long]

  def monotonic(unit: TimeUnit): F[Long]
  
}
```

Important: this is NOT a type class, meaning that there is no coherence restriction. 
This is because the ability to inject custom implementations in time-based
logic is essential.

Example:

```scala mdoc:reset:silent
import cats.effect._
import cats.implicits._
import scala.concurrent.duration.MILLISECONDS

def measure[F[_], A](fa: F[A])
  (implicit F: Sync[F], clock: Clock[F]): F[(A, Long)] = {
  
  for {
    start  <- clock.monotonic(MILLISECONDS)
    result <- fa
    finish <- clock.monotonic(MILLISECONDS)
  } yield (result, finish - start)
}
```
