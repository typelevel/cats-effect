---
id: async
title: Async
---

`Async` is the asynchronous [Foreign Function Interface](https://en.wikipedia.org/wiki/Foreign_function_interface) (FFI) for suspending side-effectful operations that
are completed elsewhere (often on another threadpool via a future-like API).
This typeclass allows us to sequence asynchronous operations without stumbling
into [callback hell](http://callbackhell.com/) and also gives us the ability to
shift execution to other execution contexts.

## FFI

An asynchronous task is one whose results are computed somewhere else. We await
the results of that execution by giving it a callback to be invoked with the
result. That computation may fail hence the callback is of type
`Either[Throwable, A] => ()`. This awaiting  is semantic only - no threads are
blocked, the current fiber is simply descheduled until the callback completes.

This leads us directly to the simplest asynchronous FFI
```scala
trait Async[F[_]] {
  def async_[A](k: (Either[Throwable, A] => Unit) => Unit): F[A]
}
```

As an example of this in action, we can look at the definition of `Async[F].fromFuture`,
which uses `Future#onComplete` to invoke the supplied callback
```scala
def fromFuture[A](fut: F[Future[A]]): F[A] =
  flatMap(fut) { f =>
    flatMap(executionContext) { implicit ec =>
      async_[A](cb => f.onComplete(t => cb(t.toEither)))
    }
  }
```

`async_` is somewhat constrained however. We can't perform any `F` effects
in the process of registering the callback and we also can't register
a finalizer to cancel the asynchronous task in the event that the fiber
running `async_` is canceled.

`Async` therefore provides the more general `async` as well
```scala
trait Async[F[_]] {
  def async[A](k: (Either[Throwable, A] => Unit) => F[Option[F[Unit]]]): F[A] = {
}
```

As you can see, it takes the same callback as before but this time we can
perform effects suspended in `F`. The `Option[F[Unit]]` allows us to
return a finalizer to be invoked if the fiber is canceled. For example, here's
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

        //Cancel the completable future if the fiber is canceled
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

## Here be dragons

`Async` also defines a function called `cont`. This is _not_ intended to be
called from user code. You also _absolutely do not_ need to understand it in
order to use `Async`. The design is however very instructive for the curious
reader. To start with, consider the definition of `async` again

```scala
trait Async[F[_]] {
  def async[A](k: (Either[Throwable, A] => Unit) => F[Option[F[Unit]]]): F[A]
```

Suppose we try to implement the inductive instance of this for `OptionT`. We
have something similar to

```scala
trait OptionTAsync[F[_]] extends Async[OptionT[F, *]] {
  implicit def delegate: Async[F]

  override def async[A](
      k: (Either[Throwable, A] => Unit) => OptionT[F, Option[OptionT[F, Unit]]])
      : OptionT[F, A] = OptionT.liftF(
    delegate.async { (cb: Either[Throwable, A] => Unit) =>
      val x: F[Option[Option[OptionT[F, Unit]]]] = k(cb).value
      delegate.map(x) { (o: Option[Option[OptionT[F, Unit]]]) =>
        o.flatten.map { (opt: OptionT[F, Unit]) => opt.value.void }
      }
    }
  )
}
```

This looks (vaguely) reasonable. However, there is a subtle problem lurking in
there -  the use of `flatten`. The problem is that `OptionT` (and similarly
`EitherT` and `IorT`) has an additional error channel (where `x = 
delegate.pure(None)`) that we are forced to discard when mapping to something
of type `F[Option[F[Unit]]]`.

This in fact breaks several of the `Async` laws:

```scala
def asyncRightIsSequencedPure[A](a: A, fu: F[Unit]) =
  F.async[A](k => F.delay(k(Right(a))) >> fu.as(None)) <-> (fu >> F.pure(a))

def asyncLeftIsSequencedRaiseError[A](e: Throwable, fu: F[Unit]) =
  F.async[A](k => F.delay(k(Left(e))) >> fu.as(None)) <-> (fu >> F.raiseError(e))
```

In both cases if we have `fu = OptionT.none[F, A]` (the error channel for `OptionT`) then
the LHS will suppress the error, whereas the RHS will not.

A possible solution would be to
un-[CPS](https://en.wikipedia.org/wiki/Continuation-passing_style) the
computation by defining

```scala
def cont[A]: F[(Either[Throwable, A] => Unit, F[A])]
```

where the first element of the tuple `Either[Throwable, A] => Unit` is the
callback used to obtain the result of the asynchronous computation as before and
the second element (which we'll call `get`) is similar to a promise and can be
used to semantically block waiting for the result of the asynchronous
computation.

We could then define `async` in terms of `cont`

```scala
def async[A](k: (Either[Throwable, A] => Unit) => F[Option[F[Unit]]]): F[A] =
  F.uncancelable { poll =>
    cont[A].flatMap {
      case (cb, get) =>
        k(cb).flatMap {
          case Some(fin) => poll(get).onCancel(fin)
          case None => poll(get)
        }
    }
  }
```

In this case, the error channel is propagated from `k(cb)` as we directly `flatMap`
on the result.

Fantastic! We're done, right? Well... not quite. The problem is that it is not safe
to call concurrent operations such as `get.start`. We therefore need to employ
one more trick and restrict the operations in scope using higher-rank polymorphism.

```scala
def cont[K, R](body: Cont[F, K, R]): F[R]

//where
trait Cont[F[_], K, R] {
  def apply[G[_]](
      implicit
      G: MonadCancel[G, Throwable]): (Either[Throwable, K] => Unit, G[K], F ~> G) => G[R]
}
```

This strange formulation means that only operations up to those defined by
`MonadCancel` are in scope within the body of `Cont`. The third element of the
tuple is a natural transformation used to lift `k(cb)` and any finalizers into
`G`. With that in place, the implementation is actually almost identical to what we
had above, but statically prohibits us from calling unsafe operations.

```scala
def async[A](k: (Either[Throwable, A] => Unit) => F[Option[F[Unit]]]): F[A] = {
  val body = new Cont[F, A, A] {
    def apply[G[_]](implicit G: MonadCancel[G, Throwable]) = { (resume, get, lift) =>
      G.uncancelable { poll =>
        lift(k(resume)).flatMap {
          case Some(fin) => poll(get).onCancel(lift(fin))
          case None => poll(get)
        }
      }
    }
  }

  cont(body)
}
```

The `Cont` model is also substantially cleaner to implement internally,
particularly with respect to cancelation and finalization. With `cont` a
finalizer can only be introduced via `onCancel`, as opposed to `async` where
finalizers can be introduced either via `onCancel` or by returning
`Some(finalizer)` from the continuation.
