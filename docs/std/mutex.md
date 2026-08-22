---
id: mutex
title: Mutex
---

`Mutex` is a concurrency primitive that can be used to give access to a resource to only **one**
fiber at a time. Basically, it's a [`Semaphore`](./semaphore.md) with a single available permit.

```scala mdoc:silent
import cats.effect.Resource

trait Mutex[F[_]] {
  def lock: Resource[F, Unit]
}
```

**Caution**: This lock is not reentrant,
thus doing `mutex.lock.surround(mutex.lock.use_)`
will deadlock.

## Using `Mutex`

```scala mdoc:reset:silent
import cats.effect.{IO, Ref}
import cats.effect.std.Mutex

trait State
class Service(mutex: Mutex[IO], ref: Ref[IO, State]) {
  def modify(f: State => IO[State]): IO[Unit] =
    mutex.lock.surround {
      for {
        current <- ref.get
        next <- f(current)
        _ <- ref.set(next)
      } yield ()
    }
}
```
