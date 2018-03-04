---
layout: docs
title:  "Async"
number: 4
source: "core/shared/src/main/scala/cats/effect/Async.scala"
scaladoc: "#cats.effect.Async"
---

# Async

A `Monad` that can describe asynchronous or synchronous computations that produce exactly one result.

```scala
import cats.effect.{LiftIO, Sync}
import scala.concurrent.ExecutionContext

trait Async[F[_]] extends Sync[F] with LiftIO[F] {
  def async[A](k: (Either[Throwable, A] => Unit) => Unit): F[A]
  def shift(implicit ec: ExecutionContext): F[Unit] = {
    async { (cb: Either[Throwable, Unit] => Unit) =>
      ec.execute(new Runnable {
        def run() = cb(Callback.rightUnit)
      })
    }
  }
}
```

### async

The `async` method has an interesting signature that is nothing more than the representation of a callback-based API function. For example, consider the following example having an API that returns a `Future[String]` as a response:

```scala
import cats.effect.{IO, Async}
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

```

In the example above `ioa` will have a successful value `A` or it will be raise an error in the `IO` context.

### shift

The `shift` method allows you to process the next computation in the desired `ExecutionContext`. Consider the following example:

```scala
import java.util.concurrent.Executors

import cats.effect.{Async, IO, Sync}
import scala.concurrent.ExecutionContext

private val cachedThreadPool = Executors.newCachedThreadPool()
private val BlockingFileIO   = ExecutionContext.fromExecutor(cachedThreadPool)
implicit val Main: ExecutionContextExecutor = ExecutionContext.global

val ioa: IO[Unit] =
  for {
    _     <- Sync[IO].delay { println("Enter your name: ")}
    _     <- Async[IO].shift(BlockingFileIO)
    name  <- Sync[IO].delay { scala.io.StdIn.readLine() }
    _     <- Async[IO].shift
    _     <- Sync[IO].delay { println(s"Welcome $name!") }
    _     <- Sync[IO].delay(cachedThreadPool.shutdown())
  } yield ()
```

We start by asking the user to enter its name and next we thread-shift to the `BlockingFileIO` execution context because we expect the following action to block on the thread for a long time and we don't want that to happen in the main thread of execution. After the `expensive IO operation` (readLine) gets back with a response we thread-shift back to the main execution context defined as an implicit value, and finally the program ends by showing a message in the console and shutting down a thread pool, all actions run in the main execution context.
