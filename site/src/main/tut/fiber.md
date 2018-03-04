---
layout: docs
title:  "Fiber"
number: 7
source: "shared/src/main/scala/cats/effect/Fiber.scala"
scaladoc: "#cats.effect.Fiber"
---

# Fiber

It represents the (pure) result of an `Async` data type (e.g. `IO`) being started concurrently and that can be either joined or cancelled.

You can think of fibers as being lightweight threads, a fiber being a concurrency primitive for doing cooperative multi-tasking.

```scala
trait Fiber[F[+_], +A] {
  def cancel: F[Unit]
  def join: F[A]
}
```

For example a `Fiber` value is the result of evaluating `IO.start`:

```scala
import cats.effect.{Fiber, IO}
import cats.syntax.apply._

val io = IO.shift *> IO(println("Hello!"))
val fiber: IO[Fiber[IO, Unit]] = io.start
```

Usage example:

```scala
import cats.effect.IO
import cats.implicits._

val launchMissiles = IO.raiseError(new Exception("boom!"))

for {
  fiber     <- IO.shift *> launchMissiles.start
  _         <- runToBunker.handleErrorWith { error =>
                 // Retreat failed, cancel launch (maybe we should
                 // have retreated to our bunker before the launch?)
                fiber.cancel *> IO.raiseError(error)
               }
  aftermath <- fiber.join
} yield aftermath
```
