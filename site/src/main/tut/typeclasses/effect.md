---
layout: docsplus
title:  "Effect"
number: 7
source: "shared/src/main/scala/cats/effect/Effect.scala"
scaladoc: "#cats.effect.Effect"
---

A `Monad` that can suspend side effects into the `F[_]` context and supports lazy and potentially asynchronous evaluation.

```tut:silent
import cats.effect.{Async, IO}

trait Effect[F[_]] extends Async[F] {
  def runAsync[A](fa: F[A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit]
}
```

This type class is describing data types that:
- Implement the `Async` algebra.
- Implement a lawful `Effect.runAsync` operation that triggers the evaluation (in the context of `IO`).

Note: this is the safe and generic version of `IO.unsafeRunAsync` (aka Haskell's `unsafePerformIO`).

### runAsync

It represents the intention to evaluate a given effect in the context of `F[_]` asynchronously giving you back an `IO[A]`. Eg.:

```tut:silent
import cats.effect.Effect

val task = IO("Hello World!")

val ioa: IO[Unit] =
  Effect[IO].runAsync(task) {
    case Right(value) => IO(println(value))
    case Left(error)  => IO.raiseError(error)
  }

ioa.unsafeRunSync()
```
