---
layout: docsplus
title:  "Async"
number: 5
source: "core/shared/src/main/scala/cats/effect/Async.scala"
scaladoc: "#cats.effect.Async"
---

A `Monad` that can describe asynchronous or synchronous computations that produce exactly one result.

```scala mdoc:silent
import cats.effect.{LiftIO, Sync}

trait Async[F[_]] extends Sync[F] with LiftIO[F] {
  def async[A](k: (Either[Throwable, A] => Unit) => Unit): F[A]
}
```

This type class allows the modeling of data types that:
- Can start asynchronous processes.
- Can emit one result on completion.
- Can end in error

Important: on the "one result" signaling, this is not an "exactly once" requirement. At this point streaming types can implement `Async` and such an "exactly once" requirement is only clear in `Effect`.

Therefore the signature exposed by the `Async.async` builder is this:

```scala
(Either[Throwable, A] => Unit) => Unit
```

Important: such asynchronous processes are not cancelable. See the `Concurrent` alternative for that.

### On Asynchrony

An asynchronous task represents logic that executes independent of the main program flow, or current callstack. It can be a task whose result gets computed on another thread, or on some other machine on the network.

In terms of types, normally asynchronous processes are represented as:

```scala
(A => Unit) => Unit
```

This signature can be recognized in the "Observer pattern" described in the "Gang of Four", although it should be noted that without an `onComplete` event (like in the Rx Observable pattern) you can't detect completion in case this callback can be called zero or multiple times.

Some abstractions allow for signaling an error condition (e.g. `MonadError` data types), so this would be a signature that's closer to Scala's `Future.onComplete`:

```scala
(Either[Throwable, A] => Unit) => Unit
```

And many times the abstractions built to deal with asynchronous tasks also provide a way to cancel such processes, to be used in race conditions in order to cleanup resources early:

```scala
(A => Unit) => Cancelable
```

This is approximately the signature of JavaScript's `setTimeout`, which will return a "task ID" that can be used to cancel it. Important: this type class in particular is NOT describing cancelable async processes, see the `Concurrent` type class for that.

### async

The `async` method has an interesting signature that is nothing more than the representation of a callback-based API function. For example, consider the following example having an API that returns a `Future[String]` as a response:

```scala mdoc:reset:silent
import cats.effect.{IO, Async}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

val apiCall = Future.successful("I come from the Future!")

val ioa: IO[String] =
  Async[IO].async { cb =>
    import scala.util.{Failure, Success}

    apiCall.onComplete {
      case Success(value) => cb(Right(value))
      case Failure(error) => cb(Left(error))
    }
 }

ioa.unsafeRunSync()
```

In the example above `ioa` will have a successful value `A` or it will be raise an error in the `IO` context.
