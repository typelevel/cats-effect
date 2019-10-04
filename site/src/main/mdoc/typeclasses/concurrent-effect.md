---
layout: docsplus
title:  "ConcurrentEffect"
number: 8
source: "core/shared/src/main/scala/cats/effect/ConcurrentEffect.scala"
scaladoc: "#cats.effect.ConcurrentEffect"
---

Type class describing effect data types that are cancelable and can be evaluated concurrently.

In addition to the algebras of `Concurrent` and `Effect`, instances must also implement the `ConcurrentEffect.runCancelable` operation that triggers the evaluation, suspended in the `SyncIO` context, but that also returns a token that can be used for canceling the running computation.

*Note this is the safe and generic version of `IO.unsafeRunCancelable`*.

```scala mdoc:silent
import cats.effect.{Concurrent, Effect, IO, CancelToken, SyncIO}

trait ConcurrentEffect[F[_]] extends Concurrent[F] with Effect[F] {
  def runCancelable[A](fa: F[A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[CancelToken[F]]
}
```

This `runCancelable` operation actually mirrors the `cancelable` builder in [Concurrent](./concurrent.md). With the `runCancelable` and `cancelable` pair one is then able to convert between `ConcurrentEffect` data types:

```scala mdoc:reset:silent
import cats.effect._

def convert[F[_], G[_], A](fa: F[A])
  (implicit F: ConcurrentEffect[F], G: Concurrent[G]): G[A] = {
 
  G.cancelable { cb =>
    val token = F.runCancelable(fa)(r => IO(cb(r))).unsafeRunSync()
    convert[F, G, Unit](token)
  }
}
```
