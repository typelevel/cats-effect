---
id: atomic-cell
title: Atomic Cell
---

A synchronized, concurrent, mutable reference.

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

Provides safe concurrent access and modification of its contents, by ensuring only one fiber
can operate on them at the time. Thus, all operations except `get` may semantically block the
calling fiber.

## Using `AtomicCell`

The `AtomicCell` can be treated as a combination of `Mutex` and `Ref`:

```scala mdoc:reset:silent
import cats.effect.IO
import cats.effect.std.AtomicCell

trait State

class Service(cell: AtomicCell[IO, State]) {
  def modify(f: State => IO[State]): IO[Unit] =
    cell.evalUpdate(f)
}
```

### Example

Imagine a random data generator,
that requires running some effectual operations _(e.g. checking a database)_
to produce a new value.
In that case, it may be better to block than to repeat the operation.

```scala mdoc:reset:silent
import cats.effect.IO
import cats.effect.std.AtomicCell

trait Data

class RandomDataGenerator(cell: AtomicCell[IO, Data]) {
  // Generates a new random value.
  def next: IO[Data] =
    cell.evalUpdateAndGet(generate)

  private def generate(previous: Data): IO[Data] =
    ???
}
```
