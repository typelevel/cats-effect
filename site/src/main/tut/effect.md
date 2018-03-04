---
layout: docs
title:  "Effect"
number: 5
source: "shared/src/main/scala/cats/effect/Effect.scala"
scaladoc: "#cats.effect.Effect"
---
# Effect

A `Monad` that can suspend side effects into the `F[_]` context and supports lazy and potentially asynchronous evaluation.

```scala
import cats.effect.{Async, IO}

trait Effect[F[_]] extends Async[F] {
  def runAsync[A](fa: F[A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit]
}
```

`runAsync` represents the intention to evaluate a given effect in the context of `F[_]` asynchronously and gives you back an `IO[A]`. Eg.:

```scala
import cats.effect.{Effect, IO}
import monix.eval.Task

val task = Task("Hello World!")

val ioa: IO[String] =
  Effect[Task].runAsync(task) {
    case Right(value) => IO(println(value))
    case Left(error)  => IO.raiseError(error)
  }
```
