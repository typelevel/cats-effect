---
layout: docsplus
title:  "Fiber"
number: 10
source: "shared/src/main/scala/cats/effect/Fiber.scala"
scaladoc: "#cats.effect.Fiber"
---

It represents the (pure) result of an `Async` data type (e.g. `IO`) being started concurrently and that can be either joined or canceled.

You can think of fibers as being lightweight threads, a fiber being a concurrency primitive for doing cooperative multi-tasking.

```scala
trait Fiber[F[_], A] {
  def cancel: F[Unit]
  def join: F[A]
}
```

For example a `Fiber` value is the result of evaluating `IO.start`:

```scala mdoc:silent
import cats.effect.{Fiber, IO}

import scala.concurrent.ExecutionContext.Implicits.global
// Needed for `start`
implicit val ctx = IO.contextShift(global)

val io = IO(println("Hello!"))
val fiber: IO[Fiber[IO, Unit]] = io.start
```

Usage example:

```scala mdoc:reset:silent
import cats.effect.{ContextShift, IO}

import scala.concurrent.ExecutionContext

implicit val contextShift: ContextShift[IO] = IO.contextShift(ExecutionContext.global)

val launchMissiles: IO[Unit] = IO.raiseError(new Exception("boom!"))
val runToBunker = IO(println("To the bunker!!!"))

for {
  fiber <- launchMissiles.start
  _ <- runToBunker.handleErrorWith { error =>
         // Retreat failed, cancel launch (maybe we should
         // have retreated to our bunker before the launch?)
         fiber.cancel *> IO.raiseError(error)
       }
  aftermath <- fiber.join
} yield aftermath
```
