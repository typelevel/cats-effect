---
id: ref
title: Ref
---

A concurrent mutable reference.

```scala mdoc:silent
abstract class Ref[F[_], A] {
  def get: F[A]
  def set(a: A): F[Unit]
  def updateAndGet(f: A => A): F[A]
  def modify[B](f: A => (A, B)): F[B]
  // ... and more
}
```

Provides safe concurrent access and modification of its content, but no functionality for synchronisation, which is instead handled by [Deferred](./deferred.md).

For this reason, a `Ref` is always initialised to a value.

The default implementation is nonblocking and lightweight, consisting
essentially of a purely functional wrapper over an `AtomicReference`.
Consequently it _must not_ be used to store mutable data as
`AtomicReference#compareAndSet` and friends are dependent
upon object reference equality.


### Concurrent Counter

This is probably one of the most common uses of this concurrency primitive.

In this example, the workers will concurrently run and update the value of the `Ref`.

```scala mdoc:reset:silent
//> using lib "org.typelevel::cats-effect::3.5.7"

import cats.effect.{IO, IOApp, Sync}
import cats.effect.kernel.Ref
import cats.syntax.all._

class Worker[F[_]](id: Int, ref: Ref[F, Int])(implicit F: Sync[F]) {

  private def putStrLn(value: String): F[Unit] =
    F.blocking(println(value))

  def start: F[Unit] =
    for {
      c1 <- ref.get
      _  <- putStrLn(show"Worker #$id >> $c1")
      c2 <- ref.updateAndGet(x => x + 1)
      _  <- putStrLn(show"Worker #$id >> $c2")
    } yield ()
}

object RefExample extends IOApp.Simple {

  val run: IO[Unit] =
    for {
      ref <- Ref[IO].of(0)
      w1 = new Worker[IO](1, ref)
      w2 = new Worker[IO](2, ref)
      w3 = new Worker[IO](3, ref)
      _ <- List(
        w1.start,
        w2.start,
        w3.start,
      ).parSequence.void
    } yield ()
}
```

This is one possible outcome showing “Worker #id » currentCount”:

```
Worker #1 >> 0
Worker #3 >> 0
Worker #2 >> 0
Worker #2 >> 3
Worker #1 >> 1
Worker #3 >> 2
```
