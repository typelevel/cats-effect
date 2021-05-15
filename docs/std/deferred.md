---
id: deferred
title: Deferred
---

A purely functional synchronization primitive which represents a single value which may not yet be available.

When created, a `Deferred` is empty. It can then be completed exactly once, and never be made empty again.

```scala mdoc:silent
abstract class Deferred[F[_], A] {
  def get: F[A]
  def complete(a: A): F[Boolean]
}
```

Expected behavior of `get`

- `get` on an empty `Deferred` will block until the `Deferred` is completed
- `get` on a completed `Deferred` will always immediately return its content
- `get` is cancelable and on cancelation it will unsubscribe the registered
  listener, an operation that's possible for as long as the `Deferred` value
  isn't complete

Expected behavior of `complete`

- `complete(a)` on an empty `Deferred` will set it to `a`, notify any and all readers currently blocked on a call to `get` and return `true`
- `complete(a)` on a `Deferred` that has already been completed will not modify its content, and will result `false`

Albeit simple, `Deferred` can be used in conjunction with `Ref` to build complex concurrent behaviour and data structures like queues and semaphores.

Finally, the blocking mentioned above is semantic only, no actual threads are blocked by the implementation.

### Only Once

Whenever you are in a scenario when many processes can modify the same value but you only care about the first one in doing so and stop processing, then this is a great use case of `Deferred[F, A]`.

Two processes will try to complete at the same time but only one will succeed, completing the deferred primitive exactly once. The loser one will raise an error when trying to complete a deferred already completed and automatically be canceled by the `IO.race` mechanism, that’s why we call attempt on the evaluation.

```scala mdoc:reset:silent
import cats.effect.{IO, Deferred}
import cats.syntax.all._

def start(d: Deferred[IO, Int]): IO[Unit] = {
  val attemptCompletion: Int => IO[Unit] = n => d.complete(n).attempt.void

  List(
    IO.race(attemptCompletion(1), attemptCompletion(2)),
    d.get.flatMap { n => IO(println(show"Result: $n")) }
  ).parSequence.void
}

val program: IO[Unit] =
  for {
    d <- Deferred[IO, Int]
    _ <- start(d)
  } yield ()
```
