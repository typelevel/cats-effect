---
id: countdown-latch
title: Count Down Latch
---

A one-shot concurrency primitive that blocks any fibers that wait on
it. It is initialized with a positive integer `n` latches and waiting
fibers are semantically blocked until all `n` latches are released.
After this, further `await`s are no-ops (equivalent to `IO.unit`).

```scala
trait CountDownLatch[F[_]] {

  def release: F[Unit]

  def await: F[Unit]

}
```

Cancelation of `await` is supported.

```scala mdoc
import cats.implicits._
import cats.effect._
import cats.effect.std.CountDownLatch
import cats.effect.unsafe.implicits.global

val run = (
  for {
    c <- CountDownLatch[IO](2)
    f <- (c.await >> IO.println("Countdown latch unblocked")).start
    _ <- c.release
    _ <- IO.println("Before latch is unblocked")
    _ <- c.release
    _ <- f.join
  } yield ()
)

run.unsafeRunSync()
```
