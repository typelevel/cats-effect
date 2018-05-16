---
layout: docsplus
title:  "Deferred"
number: 12
source: "shared/src/main/scala/cats/effect/concurrent/Deferred.scala"
scaladoc: "#cats.effect.concurrent.Deferred"
---

A purely functional synchronization primitive which represents a single value which may not yet be available.

When created, a `Deferred` is empty. It can then be completed exactly once, and never be made empty again.

```tut:book:silent
abstract class Deferred[F[_], A] {
  def get: F[A]
  def complete(a: A): F[Unit]
}
```

### Expected behavior of `get`

- `get` on an empty `Deferred` will block until the `Deferred` is completed.
- `get` on a completed `Deferred` will always immediately return its content.

### Expected behavior of `complete`

- `complete(a)` on an empty `Deferred` will set it to `a`, and notify any and all readers currently blocked on a call to `get`.
- `complete(a)` on a `Deferred` that has already been completed will not modify its content, and result in a failed `F`.

### Notes

Albeit simple, `Deferred` can be used in conjunction with `Ref` to build complex concurrent behaviour and data structures like queues and semaphores.

Finally, the blocking mentioned above is semantic only, no actual threads are blocked by the implementation.

### Only Once

Whenever you are in a scenario when many processes can modify the same value but you only care about the first one in doing so and stop processing, then this is a great use case of `Deferred[F, A]`.

Two processes will try to complete at the same time but only one will succeed, completing the deferred primitive exactly once. The loser one will raise an error when trying to complete a deferred already completed and automatically be canceled by the `IO.race` mechanism, that’s why we call attempt on the evaluation.

```tut:book
import cats.Parallel
import cats.effect.{Concurrent, IO}
import cats.effect.concurrent.Deferred
import cats.implicits._

import scala.concurrent.ExecutionContext.Implicits.global

implicit val par: Parallel[IO, IO] = Parallel[IO, IO.Par].asInstanceOf[Parallel[IO, IO]]

def start(d: Deferred[IO, Int]): IO[Unit] = {
  val attemptCompletion: Int => IO[Unit] = n => d.complete(n).attempt.void

  List(
    IO.race(attemptCompletion(1), attemptCompletion(2)),
    d.get.flatMap { n => IO(println(s"Result: $n")) }
  ).parSequence.void
}

val program: IO[Unit] =
  for {
    d <- Deferred[IO, Int]
    _ <- start(d)
  } yield ()
```
