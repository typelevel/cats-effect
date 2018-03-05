---
layout: docs
title:  "Async"
number: 4
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

### shift

`shift`’s functionality is a little complicated, but generally speaking, you should think of it as a “force this `F[_]` onto this other thread pool” function. Of course, when `ioa` from the example above executes, most of its work isn’t done on any thread at all (since it is simply registering a hook with the kernel), and so that work isn’t thread shifted to any pool, main or otherwise. But when `ioa` gets back to us with the api call response, the callback will be handled and then immediately thread-shifted back onto the main pool, which is passed implicitly as a parameter `ExecutionContext` to shift (you can also pass this explicitly if you like). This thread-shifting means that all of the subsequent actions within the for-comprehension – which is to say, the continuation of `ioa` – will be run on the ec thread pool. This is an extremely common use-case in practice, and `IO` attempts to make it as straightforward as possible.

Another possible application of thread shifting is ensuring that a blocking `IO` action is relocated from the main, CPU-bound thread pool onto one of the pools designated for blocking IO. Consider the following example:

```tut:book
import java.util.concurrent.Executors

val cachedThreadPool = Executors.newCachedThreadPool()
val BlockingFileIO   = ExecutionContext.fromExecutor(cachedThreadPool)
implicit val Main = ExecutionContext.global

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
