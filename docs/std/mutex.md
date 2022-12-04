---
id: mutex
title: Mutex
---

`Mutex` is a concurrency primitive that can be used to give access to a resource to only **one**
fiber at a time. Basically, it's a [`Semaphore`](./semaphore.md) with a single available permit.

```scala
trait Mutex[F[_]] {
  def lock: Resource[F, Unit]
}
```

**Caution**: This lock is not reentrant, thus this `mutex.lock.surround(mutex.lock.use_)` will
deadlock.

## Using `Mutex`

```scala mdoc:silent
import cats.effect.{IO, Ref}
import cats.effect.std.Mutex

trait State
class Service(mutex: Mutex[IO], ref: Ref[IO, State]) {
  def withState(f: State => IO[Unit]): IO[Unit] = 
    mutex.lock.surround {
      for {
        current <- ref.get
        _ <- f(current)
      } yield ()
    }
}
```
