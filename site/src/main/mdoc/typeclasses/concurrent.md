---
layout: docsplus
title:  "Concurrent"
number: 6
source: "core/shared/src/main/scala/cats/effect/Concurrent.scala"
scaladoc: "#cats.effect.Concurrent"
---

Type class for `Async` data types that are cancelable and can be started concurrently.

Thus this type class allows abstracting over data types that:

- Implement the `Async` algebra, with all its restrictions.
- Can provide logic for cancellation, to be used in race conditions in order to release resources early (in its `Concurrent.cancelable` builder).

Due to these restrictions, this type class also affords to describe a `Concurrent.start` operation that can start async processing, suspended in the context of `F[_]` and that can be canceled or joined.

Without cancellation being baked in, we couldn't afford to do it.

```scala mdoc:silent
import cats.effect.{Async, Fiber, CancelToken}

trait Concurrent[F[_]] extends Async[F] {
  def start[A](fa: F[A]): F[Fiber[F, A]]
  
  def race[A, B](lh: F[A], rh: F[B]): F[Either[A, B]]
  def racePair[A, B](lh: F[A], rh: F[B]): F[Either[(A, Fiber[F, B]), (Fiber[F, A], B)]]
  def cancelable[A](k: (Either[Throwable, A] => Unit) => CancelToken[F]): F[A]
}
```

Notes: 

- this type class is defined by `start` and by `racePair`
- `race` is derived from `racePair`
- `cancelable` is derived from `asyncF` and from `bracketCase`, however it is expected to be overridden in instances for optimization purposes

### Cancelable Builder

The signature exposed by the `Concurrent.cancelable` builder is this:

```scala
(Either[Throwable, A] => Unit) => CancelToken[F]
```

`CancelToken[F]` is simply an alias for `F[Unit]` and is used to represent a cancellation action which will send a signal to the producer, that may observe it and cancel the asynchronous process.

### On Cancellation

Simple asynchronous processes, like Scala's `Future`, can be described with this very basic and side-effectful type and you should recognize what is more or less the signature of `Future.onComplete` or of `Async.async` (minus the error handling):

```scala
(A => Unit) => Unit
```

But many times the abstractions built to deal with asynchronous tasks can also provide a way to cancel such processes, to be used in race conditions in order to cleanup resources early, so a very basic and side-effectful definition of asynchronous processes that can be canceled would be:

```scala
(A => Unit) => Cancelable
```

This is approximately the signature of JavaScript's `setTimeout`, which will return a "task ID" that can be used to cancel it. Or of Java's `ScheduledExecutorService.schedule`, which will return a Java `ScheduledFuture` that has a `.cancel()` operation on it.

Similarly, for `Concurrent` data types, we can provide cancellation logic, that can be triggered in race conditions to cancel the on-going processing, only that `Concurrent`'s cancelable token is an action suspended in a `CancelToken[F]`, which is nothing more than an `F[Unit]`. See `IO.cancelable`.

Suppose you want to describe a "sleep" operation, like that described by `Timer` to mirror Java's `ScheduledExecutorService.schedule` or JavaScript's `setTimeout`:

```scala
def sleep(d: FiniteDuration): F[Unit]
```

This signature is in fact incomplete for data types that are not cancelable, because such equivalent operations always return some cancellation token that can be used to trigger a forceful interruption of the timer. This is not a normal "dispose" or "finally" clause in a try/catch block, because "cancel" in the context of an asynchronous process is "concurrent" with the task's own run-loop.

To understand what this means, consider that in the case of our `sleep` as described above, on cancellation we'd need a way to signal to the underlying `ScheduledExecutorService` to forcefully remove the scheduled `Runnable` from its internal queue of scheduled tasks, "before" its execution. Therefore, without a cancelable data type, a safe signature needs to return a cancellation token, so it would look like this:

```scala
def sleep(d: FiniteDuration): F[(F[Unit], F[Unit])]
```

This function is returning a tuple, with one `F[Unit]` to wait for the completion of our sleep and a second `F[Unit]` to cancel the scheduled computation in case we need it. This is in fact the shape of `Fiber`'s API. And this is exactly what the `Concurrent.start` operation returns.

The difference between a `Concurrent` data type and one that is only `Async` is that you can go from any `F[A]` to a `F[Fiber[F, A]]`, to participate in race conditions and that can be canceled should the need arise, in order to trigger an early release of allocated resources.

Thus a `Concurrent` data type can safely participate in race conditions, whereas a data type that is only `Async` cannot without exposing and forcing the user to work with cancellation tokens. An `Async` data type cannot expose for example a `start` operation that is safe.
