---
id: atomic-cell
title: Atomic Cell
---

A synchronized, concurrent, mutable reference.

Provides safe concurrent access and modification of its contents, by ensuring only one fiber
can operate on them at the time. Thus, all operations except `get` may semantically block the
calling fiber.

```scala mdoc:silent
abstract class AtomicCell[F[_], A] {
  def get: F[A]
  def set(a: A): F[Unit]
  def modify[B](f: A => (A, B)): F[B]
  def evalModify[B](f: A => F[(A, B)]): F[B]
  def evalUpdate(f: A => F[A]): F[Unit]
  // ... and more
}
```

## Using `AtomicCell`

The `AtomicCell` can be treated as a combination of `Mutex` and `Ref`:
```scala mdoc:reset:silent
import cats.effect.{IO, Ref}
import cats.effect.std.Mutex

trait State
class Service(mtx: Mutex[IO], ref: Ref[IO, State]) {
  def modify(f: State => IO[State]): IO[Unit] = 
    mtx.lock.surround {
      for {
        current <- ref.get
        next <- f(current)
        _ <- ref.set(next) 
      } yield ()
    }
}
```

The following is the equivalent of the example above:
```scala mdoc:reset:silent
import cats.effect.IO
import cats.effect.std.AtomicCell

trait State
class Service(cell: AtomicCell[IO, State]) {
  def modify(f: State => IO[State]): IO[Unit] = 
    cell.evalUpdate(current => f(current))
}
```
