---
layout: docs
title:  "Async"
number: 5
source: "core/shared/src/main/scala/cats/effect/Async.scala"
scaladoc: "#cats.effect.Async"
---

# Async

A `Monad` that can describe asynchronous or synchronous computations that produce exactly one result.

```tut:book:silent
import cats.effect.{LiftIO, Sync}
import scala.concurrent.ExecutionContext

trait Async[F[_]] extends Sync[F] with LiftIO[F] {
  def async[A](k: (Either[Throwable, A] => Unit) => Unit): F[A]
  def shift(implicit ec: ExecutionContext): F[Unit]
}
```

### async

The `async` method has an interesting signature that is nothing more than the representation of a callback-based API function. For example, consider the following example having an API that returns a `Future[String]` as a response:

```tut:book
import cats.effect.{IO, Async}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.util.{Success, Failure}

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
