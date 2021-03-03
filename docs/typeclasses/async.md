---
id: async
title: Async
---

`Async` is the asynchronous FFI for suspending side-effectful operations.

## FFI

An asynchronous task is one whose results are computed somewhere else. We await
the results of that execution by giving it a callback to be invoked with the
result. That computation may fail hence the callback is of type
`Either[Throwable, A] => ()`.

This leads us directly to the simplest asynchronous FFI
```scala
trait Async[F[_]] {
  def async_[A](k: (Either[Throwable, A] => Unit) => Unit): F[A]
}
```

As in example of this in action, we can look at the definition of `Async[F].fromFuture`,
which uses `Future#onComplete` to invoke the supplied callback
```scala
def fromFuture[A](fut: F[Future[A]]): F[A] =
  flatMap(fut) { f =>
    flatMap(executionContext) { implicit ec =>
      async_[A](cb => f.onComplete(t => cb(t.toEither)))
    }
  }
```

`async_` is somewhat contrained however. We can't perform any `F` effects
in the process of registering the callback and we also can't register
a finalizer to cancel the asynchronous task in the event that the fiber
running `async_` is cancelled.

`Async` therefore provides the more general `async` as well
```scala
trait Async[F[_]] {
  def async[A](k: (Either[Throwable, A] => Unit) => F[Option[F[Unit]]]): F[A] = {
}
```

As you can see, it takes the same callback as before but this time we can
perform effects suspended in `F`. The `Option[F[Unit]]` allows us to
return a finalizer to be invoked if the fiber is cancelled. For example, here's
a simplified version of `Async[F].fromCompletableFuture`

```scala
def fromCompletableFuture[A](fut: F[CompletableFuture[A]]): F[A] =
  flatMap(fut) { cf =>
    async[A] { cb =>
      delay {
        //Invoke the callback with the result of the completable future
        val stage = cf.handle[Unit] {
          case (a, null) => cb(Right(a))
          case (_, e) => cb(Left(e))
        }

        //Cancel the completable future if the fiber is cancelled
        Some(void(delay(stage.cancel(false))))
      }
    }
  }
```

## Threadpool shifting

`Async` has the ability to shift execution to a different threadpool.

```scala
trait Async[F[_]] {
  //Get the current execution context
  def executionContext: F[ExecutionContext]
  
  //Evaluate fa on the supplied execution context and
  //then shift execution back
  def evalOn[A](fa: F[A], ec: ExecutionContext): F[A]

}
```

If you are familiar with the reader monad then this is very similar - 
`executionContext` is like `ask` and gives us the current execution context.
`evalOn` is like `local` and allows us to _locally_ change the execution
context for a given computation. After this computation is complete,
execution will return to the context specified by `executionContext`

```scala
val printThread: IO[Unit] =
  IO.executionContext.flatMap(IO.println(_))

for {
  _ <- printThread //io-compute-1
  _ <- IO.evalOn(printThread, someEc) //some-ec-thread
  _ <- printThread //io-compute-1
} yield ()
```
