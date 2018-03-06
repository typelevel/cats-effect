---
layout: docs
title:  "Effect"
number: 7
source: "shared/src/main/scala/cats/effect/Effect.scala"
scaladoc: "#cats.effect.Effect"
---
# Effect

A `Monad` that can suspend side effects into the `F[_]` context and supports lazy and potentially asynchronous evaluation.

```tut:book:silent
import cats.effect.{Async, IO}

trait Effect[F[_]] extends Async[F] {
  def runAsync[A](fa: F[A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit]
}
```

`runAsync` represents the intention to evaluate a given effect in the context of `F[_]` asynchronously and gives you back an `IO[A]`. Eg.:

```tut:book
import cats.effect.Effect

val task = IO("Hello World!")

val ioa: IO[Unit] =
  Effect[IO].runAsync(task) {
    case Right(value) => IO(println(value))
    case Left(error)  => IO.raiseError(error)
  }

ioa.unsafeRunSync()
```
