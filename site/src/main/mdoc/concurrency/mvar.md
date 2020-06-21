---
layout: docsplus
title:  "MVar"
number: 12
source: "shared/src/main/scala/cats/effect/concurrent/MVar.scala"
scaladoc: "#cats.effect.concurrent.MVar2"
---

{:.responsive-pic}
![concurrency mvar](../img/concurrency-mvar.png)

An `MVar2` is a mutable location that can be empty or contain a value,
asynchronously blocking reads when empty and blocking writes when full.

```scala
abstract class MVar2[F[_], A] {
  def put(a: A): F[Unit]
  def swap(b: A): F[A]
  def take: F[A]
  def read: F[A]

  def tryPut(a: A): F[Boolean]
  def tryTake: F[Option[A]]
  def tryRead: F[Option[A]]
}
```

**Update 2.2.0:** `MVar2` adds new methods to the `MVar` interface 
without breaking binary compatibility. Please upgrade to `MVar2` 
that supports `tryRead` and `swap`.  

## Introduction

Use-cases:

1. As synchronized, thread-safe mutable variables
2. As channels, with `take` and `put` acting as "receive" and "send"
3. As a binary semaphore, with `take` and `put` acting as "acquire" and "release"

It has these fundamental (atomic) operations:

- `put`: fills the `MVar` if it is empty, or blocks (asynchronously)
  if the `MVar` is full, until the given value is next in line to be
  consumed on `take`
- `take`: tries reading the current value (also emptying it), or blocks (asynchronously)
  until there is a value available, at which point the operation resorts
  to a `take` followed by a `put`
- `read`: which reads the current value without modifying the `MVar`,
  assuming there is a value available, or otherwise it waits until a value
  is made available via `put`
- `swap`: put a new value and return the taken one. It is not atomic.
- `tryRead`: returns the value immediately and None if it is empty.
- `tryPut` and `tryTake` variants of the above, that try
  those operation once and fail in case (semantic) blocking would
  be involved

<p class="extra" markdown='1'>
In this context "<i>asynchronous blocking</i>" means that we are not blocking
any threads. Instead the implementation uses callbacks to notify clients
when the operation has finished (notifications exposed by means of [Async](../typeclasses/async.md) or
[Concurrent](../typeclasses/concurrent.md) data types such as [IO](../datatypes/io.md))
and it thus works on top of JavaScript as well.
</p>

### Inspiration

This data type is inspired by `Control.Concurrent.MVar` from Haskell, introduced in the paper
[Concurrent Haskell](http://research.microsoft.com/~simonpj/papers/concurrent-haskell.ps.gz),
by Simon Peyton Jones, Andrew Gordon and Sigbjorn Finne, though some details of
their implementation are changed (in particular, a put on a full `MVar` used
to error, but now merely blocks).

Appropriate for building synchronization primitives and  performing simple
interthread communication, it's the equivalent of a `BlockingQueue(capacity = 1)`,
except that there's no actual thread blocking involved and it is powered by data types such as `IO`.

## Use-case: Synchronized Mutable Variables

```scala mdoc:invisible
import cats.effect.laws.util.TestContext
import cats.effect.IO

implicit val ec = TestContext()
implicit val cs = IO.contextShift(ec)
```

```scala mdoc:silent
import cats.effect._
import cats.effect.concurrent._

def sum(state: MVar2[IO, Int], list: List[Int]): IO[Int] =
  list match {
    case Nil => state.take
    case x :: xs =>
      state.take.flatMap { current =>
        state.put(current + x).flatMap(_ => sum(state, xs))
      }
  }

MVar.of[IO, Int](0).flatMap(sum(_, (0 until 100).toList))
```

This sample isn't very useful, except to show how `MVar` can be used
as a variable. The `take` and `put` operations are atomic.
The `take` call will (asynchronously) block if there isn't a value
available, whereas the call to `put` blocks if the `MVar` already
has a value in it waiting to be consumed.

Obviously after the call for `take` and before the call for `put` happens
we could have concurrent logic that can update the same variable.
While the two operations are atomic by themselves, a combination of them
isn't atomic (i.e. atomic operations don't compose), therefore if we want
this sample to be *safe*, then we need extra synchronization.

## Use-case: Asynchronous Lock (Binary Semaphore, Mutex)

The `take` operation can act as "acquire" and `put` can act as the "release".
Let's do it:

```scala mdoc:silent
final class MLock(mvar: MVar2[IO, Unit]) {
  def acquire: IO[Unit] =
    mvar.take

  def release: IO[Unit] =
    mvar.put(())

  def greenLight[A](fa: IO[A]): IO[A] =
    acquire.bracket(_ => fa)(_ => release)
}

object MLock {
  def apply(): IO[MLock] =
    MVar[IO].of(()).map(ref => new MLock(ref))
}
```

## Use-case: Producer/Consumer Channel

An obvious use-case is to model a simple producer-consumer channel.

Say that you have a producer that needs to push events.
But we also need some back-pressure, so we need to wait on the
consumer to consume the last event before being able to generate
a new event.

```scala mdoc:reset:silent
import cats.effect._
import cats.effect.concurrent._

// Signaling option, because we need to detect completion
type Channel[A] = MVar2[IO, Option[A]]

def producer(ch: Channel[Int], list: List[Int]): IO[Unit] =
  list match {
    case Nil =>
      ch.put(None) // we are done!
    case head :: tail =>
      // next please
      ch.put(Some(head)).flatMap(_ => producer(ch, tail))
  }

def consumer(ch: Channel[Int], sum: Long): IO[Long] =
  ch.take.flatMap {
    case Some(x) =>
      // next please
      consumer(ch, sum + x)
    case None =>
      IO.pure(sum) // we are done!
  }

// ContextShift required for
// 1) MVar.empty
// 2) IO.start
// for ContextShift documentation see https://typelevel.org/cats-effect/datatypes/contextshift.html
import scala.concurrent.ExecutionContext
implicit val cs = IO.contextShift(ExecutionContext.Implicits.global)

for {
  channel <- MVar[IO].empty[Option[Int]]
  count = 100000
  producerTask = producer(channel, (0 until count).toList)
  consumerTask = consumer(channel, 0L)

  fp  <- producerTask.start
  fc  <- consumerTask.start
  _   <- fp.join
  sum <- fc.join
} yield sum
```

Running this will work as expected. Our `producer` pushes values
into our `MVar` and our `consumer` will consume all of those values.
