---
layout: docsplus
title:  "ConcurrentEffect"
number: 8
source: "core/shared/src/main/scala/cats/effect/ConcurrentEffect.scala"
scaladoc: "#cats.effect.ConcurrentEffect"
---

Type class describing effect data types that are cancelable and can be evaluated concurrently.

In addition to the algebras of `Concurrent` and `Effect`, instances must also implement the `ConcurrentEffect.runCancelable` operation that triggers the evaluation, suspended in the `IO` context, but that also returns a token that can be used for cancelling the running computation.

*Note this is the safe and generic version of `IO.unsafeRunCancelable`*.

```tut:book:silent
import cats.effect.{Concurrent, Effect, IO}

trait ConcurrentEffect[F[_]] extends Concurrent[F] with Effect[F] {
  def runCancelable[A](fa: F[A])(cb: Either[Throwable, A] => IO[Unit]): IO[IO[Unit]]
}
```
