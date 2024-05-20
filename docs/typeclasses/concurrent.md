---
id: concurrent
title: Concurrent
---

This typeclass extends `Spawn` with the capability to allocate concurrent state
in the form of [Ref](../std/ref.md) and [Deferred](../std/deferred.md) and
to perform various operations which require the allocation of concurrent state,
including `memoize` and `parTraverseN`. `Ref` and `Deferred` are the concurrent
primitives necessary to implement arbitrarily complicated concurrent state machines.

### Memoization

We can memoize an effect so that it's only run once and the result used repeatedly.

```scala
def memoize[A](fa: F[A]): F[F[A]]
```

Usage looks like this:

```scala mdoc
import cats.effect.IO
import cats.effect.unsafe.implicits.global
 
val action: IO[String] = IO.println("This is only printed once").as("action")

val x: IO[String] = for {
  memoized <- action.memoize
  res1 <- memoized
  res2 <- memoized
} yield res1 ++ res2

x.unsafeRunSync()
```

### Why `Ref` and `Deferred`?

It is worth considering why `Ref` and `Deferred` are the primitives exposed by `Concurrent`.
Generally when implementing concurrent data structures we need access to the following:
- A way of allocating and atomically modifying state
- A means of waiting on a condition (semantic blocking)

Well this is precisely `Ref` and `Deferred` respectively! Consider for example,
implementing a `CountDownLatch`, which is instantiated with `n > 0` latches and
allows fibers to semantically block until all `n` latches are released. We can
model this situation with the following state

```scala
sealed trait State[F[_]]
case class Awaiting[F[_]](latches: Int, signal: Deferred[F, Unit]) extends State[F]
case class Done[F[_]]() extends State[F]
```

representing the fact that the countdown latch either has latches which have yet to be released
and so fibers should block on it using the `signal` (more on this in a minute) or all the
latches have been released and the countdown latch is done.

We can store this state in a `state: Ref[F, State[F]]` to allow for concurrent
modification. Then the implementation of await looks like this:
```scala
def await: F[Unit] =
  state.get.flatMap {
    case Awaiting(_, signal) => signal.get
    case Done() => F.unit
  }
```
As you can see, if we're still waiting for some of the latches to be released then we 
use `signal` to block. Otherwise we just pass through with `F.unit`.

Similarly the implementation of `release` is:
```scala
def release: F[Unit] =
  F.uncancelable { _ =>
    state.modify {
      case Awaiting(n, signal) =>
        if (n > 1) (Awaiting(n - 1, signal), F.unit) else (Done(), signal.complete(()).void)
      case d @ Done() => (d, F.unit)
    }.flatten
  }
```

Ignoring subtleties around cancelation, the implementation is straightforward. If there is more
than 1 latch remaining then we simply decrement the count. If we are already done then we do nothing. The interesting case is when there is precisely 1 latch remaining, in which case we
transition to the `Done` state and we also `complete` the signal which unblocks all the
fibers waiting on the countdown latch.

There has been plenty of excellent material written on this subject. See [here](https://typelevel.org/blog/2020/10/30/concurrency-in-ce3.html) and [here](https://systemfw.org/writings.html) for example.
