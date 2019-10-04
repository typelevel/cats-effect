---
layout: docsplus
title:  "Ref"
number: 13
source: "shared/src/main/scala/cats/effect/concurrent/Ref.scala"
scaladoc: "#cats.effect.concurrent.Ref"
---

{:.responsive-pic}
![concurrency ref](../img/concurrency-ref.png)

An asynchronous, concurrent mutable reference.

```scala mdoc:silent
abstract class Ref[F[_], A] {
  def get: F[A]
  def set(a: A): F[Unit]
  def modify[B](f: A => (A, B)): F[B]
  // ... and more
}
```

Provides safe concurrent access and modification of its content, but no functionality for synchronisation, which is instead handled by `Deferred`.

For this reason, a `Ref` is always initialised to a value.

The default implementation is nonblocking and lightweight, consisting essentially of a purely functional wrapper over an `AtomicReference`.

### Concurrent Counter

This is probably one of the most common uses of this concurrency primitive.

The workers will concurrently run and modify the value of the Ref so this is one possible outcome showing “#worker » currentCount”:

```
#2 >> 0
#1 >> 0
#3 >> 0
#1 >> 0
#3 >> 2
#2 >> 1
```

```scala mdoc:reset:silent
import cats.effect.{IO, Sync}
import cats.effect.concurrent.Ref
import cats.implicits._
import scala.concurrent.ExecutionContext

// Needed for triggering evaluation in parallel
implicit val ctx = IO.contextShift(ExecutionContext.global)

class Worker[F[_]](number: Int, ref: Ref[F, Int])(implicit F: Sync[F]) {

  private def putStrLn(value: String): F[Unit] = F.delay(println(value))

  def start: F[Unit] =
    for {
      c1 <- ref.get
      _  <- putStrLn(show"#$number >> $c1")
      c2 <- ref.modify(x => (x + 1, x))
      _  <- putStrLn(show"#$number >> $c2")
    } yield ()

}

val program: IO[Unit] =
  for {
    ref <- Ref.of[IO, Int](0)
    w1  = new Worker[IO](1, ref)
    w2  = new Worker[IO](2, ref)
    w3  = new Worker[IO](3, ref)
    _   <- List(
             w1.start,
             w2.start,
             w3.start
           ).parSequence.void
  } yield ()
```
