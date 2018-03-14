---
layout: docs
title:  "IO"
number: 9
source: "shared/src/main/scala/cats/effect/IO.scala"
scaladoc: "#cats.effect.IO"
---
# IO

A data type for encoding side effects as pure values, capable of expressing both synchronous and asynchronous computations.

Effects contained within this abstraction are not evaluated until the "end of the world", which is to say, when one of the "unsafe" methods are used. Effectful results are not memoized, meaning that memory overhead is minimal (and no leaks), and also that a single effect may be run multiple times in a referentially-transparent manner. For example:

```tut:book
import cats.effect.IO

val ioa = IO { println("hey!") }

val program: IO[Unit] =
  for {
     _ <- ioa
     _ <- ioa
  } yield ()

program.unsafeRunSync()
```

The above example prints "hey!" twice, as the effect re-runs each time it is sequenced in the monadic chain.

## On Referential Transparency and Lazy Evaluation

`IO` can suspend side effects and is thus a lazily evaluated data type, being many times compared with `Future` from the standard library and to understand the landscape in terms of the evaluation model (in Scala), consider this classification:

|                    |        Eager        |            Lazy            |
|:------------------:|:-------------------:|:--------------------------:|
| **Synchronous**    |          A          |           () => A          |
|                    |                     | [Eval[A]](https://typelevel.org/cats/datatypes/eval.html) |
| **Asynchronous**   | (A => Unit) => Unit | () => (A => Unit) => Unit  |
|                    |      Future[A]      |          IO[A]             |

In comparison with Scala's `Future`, the `IO` data type preserves _referential transparency_ even when dealing with side effects and is lazily evaluated. In an eager language like Scala, this is the difference between a result and the function producing it.

Similar with `Future`, with `IO` you can reason about the results of asynchronous processes, but due to its purity and laziness `IO` can be thought of as a specification (to be evaluated at the "_end of the world_"), yielding more control over the evaluation model and being more predictable, for example when dealing with sequencing vs parallelism, when composing multiple IOs or when dealing with failure.

Note laziness goes hand in hand with referential transparency. Consider this example:

```scala
for {
  _ <- addToGauge(32)
  _ <- addToGauge(32)
} yield ()
```

If we have referential transparency, we can rewrite that example as:

```scala
val task = addToGauge(32)

for {
  _ <- task
  _ <- task
} yield ()
```

This doesn't work with `Future`, but works with `IO`.

## Basic Operations

`IO` implements all the typeclasses shown in the hierarch. Therefore all these operations are available for `IO`, in addition to some others.

### apply

It probably is the most used operation and, as explained before, the equivalent of `Sync[IO].delay`:

```tut:book
def apply[A](body: => A): IO[A] = ???
```

The idea is to wrap synchronous side effects such as reading / writing from / to the console:

```tut:book
def putStrlLn(value: String) = IO(println(value))
val readLn = IO(scala.io.StdIn.readLine)
```

A good practice is also to keep the granularity so please don't do something like this:

```scala
IO {
  readingFile
  writingToDatabase
  sendBytesOverTcp
  launchMissiles
}
```

In FP we embrace reasoning about our programs and since `IO` is a `Monad` you can compose bigger programs from small ones in a `for-comprehention` for example:

```
val program =
  for {
    _     <- putStrlLn("Please enter your name:")
    name  <- readLn
    _     <- putStrlLn(s"Hi $name!")
  } yield ()
```

Here you have a simple prompt program that is, at the same time, composable with other programs. Monads compose ;)

### pure

Sometimes you want to lift pure values into `IO`. For that purpose the following method is defined:

```tut:book
def pure[A](a: A): IO[A] = ???
```

For example we can lift a number (pure value) into `IO` and compose it with another `IO` that wraps a side a effect in a safe manner, nothing is going to be executed:

```tut:book
IO.pure(25).flatMap(n => IO(println(s"Number is: $n")))
```

You should never use `pure` to wrap side effects, that is very much wrong, so please don't do this:

```tut:book
IO.pure(println("THIS IS WRONG!"))
```

This will be stricly evaluated (immediately) and that's something you wouldn't want when working with side effects.

See above in the previous example how from a pure value we `flatMap` with an `IO` that wraps a side effect. That's fine. However, using `map` in similar cases is not recommended since this function is only meant for pure transformations and not to enclose side effects. For example, this works but it is fundamentally wrong:

```tut:book
IO.pure(123).map(n => println(s"NOT RECOMMENDED! $n"))
```

### unit & never

In addition to `apply` and `pure` there are two useful functions that are just aliases, namely `unit` and `never`.

`unit` is simply an alias for `pure(())`:

```tut:book
val unit: IO[Unit] = IO.pure(())
```

`never` represents a non-terminating `IO` defined in terms of `async`:
```tut:book
val never: IO[Nothing] = IO.async(_ => ())
```

## Conversions

There are two useful operations defined in the `IO` companion object to lift both a scala `Future` and an `Either` into `IO`.

### fromFuture

Constructs an `IO` which evaluates the given `Future` and produces either a result or a failure. It is defined as follow:

```tut:book
import scala.concurrent.Future

def fromFuture[A](iof: IO[Future[A]]): IO[A] = ???
```

Because `Future` eagerly evaluates, as well as because it memoizes, this function takes its parameter as an `IO`, which could be lazily evaluated. If this laziness is appropriately threaded back to the definition site of the `Future`, it ensures that the computation is fully managed by `IO` and thus referentially transparent.

Lazy evaluation, equivalent with by-name parameters:

```tut:book
import scala.concurrent.ExecutionContext.Implicits.global

IO.fromFuture(IO {
  Future(println("I come from the Future!"))
})
```

Eager evaluation:

```tut:book
val f = Future.successful("I come from the Future!")

IO.fromFuture(IO.pure(f))
```

### fromEither

Lifts an `Either[Throwable, A]` into the `IO[A]` context raising the throwable if it exists.

```tut:book
def fromEither[A](e: Either[Throwable, A]): IO[A] = e.fold(IO.raiseError, IO.pure)
```

## Error Handling

Since there is an instance of `MonadError[IO, Throwable]` available in Cats Effect, all the error handling is done through it. This means you can use all the operations available for `MonadError` and thus for `ApplicativeError` on `IO` as long as the error type is a `Throwable`. Operations such as `raiseError`, `attempt`, `handleErrorWith`, `recoverWith`, etc. Just make sure you have the syntax import in scope such as `cats.implicits._`.

### raiseError

Constructs an `IO` which sequences the specified exception.

```tut:nofail
val boom = IO.raiseError(new Exception("boom"))
boom.unsafeRunSync()
```

### attempt

Materializes any sequenced exceptions into value space, where they may be handled. This is analogous to the `catch` clause in `try`/`catch`, being the inverse of `IO.raiseError`. Example:

```tut:book
boom.attempt.unsafeRunSync()
```

Look at the [MonadError](https://github.com/typelevel/cats/blob/master/core/src/main/scala/cats/MonadError.scala) typeclass for more.

### Example: Retrying with Exponential Backoff

With `IO` you can easily model a loop that retries evaluation until success or some other condition is met.

For example here's a way to implement retries with exponential back-off:

```tut:silent
import cats.effect._
import cats.syntax.all._
import scala.concurrent.duration._

def retryWithBackoff[A](ioa: IO[A], initialDelay: FiniteDuration, maxRetries: Int)
  (implicit timer: Timer[IO]): IO[A] = {

  ioa.handleErrorWith { error =>
    if (maxRetries > 0)
      IO.sleep(initialDelay) *> retryWithBackoff(ioa, initialDelay * 2, maxRetries - 1)
    else
      IO.raiseError(error)
  }
}
```

## Thread Shifting

`IO` provides a function `shift` to give you more control over the execution of your operations.

### shift

Note there are 2 overloads of the `IO.shift` function:
- One that takes an `Timer` that manages the thread-pool used to trigger async boundaries.
- Another that takes a Scala `ExecutionContext` as the thread-pool.

***Please use the former by default and use the latter only for fine-grained control over the thread pool in use.***

Examples:

By default, `Cats Effect` provides an instance of `Timer[IO]` that manages thread-pools. Eg.:

```tut:book
import cats.effect.Timer

val ioTimer = Timer[IO]
```

We can introduce an asynchronous boundary in the `flatMap` chain before a certain task:

```tut:book
val task = IO(println("task"))

IO.shift(ioTimer).flatMap(_ => task)
```

Or using `Cats` syntax:

```tut:book
import cats.syntax.apply._

IO.shift(ioTimer) *> task
// equivalent to
Timer[IO].shift *> task
```

Or we can specify an asynchronous boundary "after" the evaluation of a certain task:

```tut:book
task.flatMap(a => IO.shift(ioTimer).map(_ => a))
```

Or using `Cats` syntax:

```tut:book
task <* IO.shift(ioTimer)
// equivalent to
task <* Timer[IO].shift
```

Example of where this might be useful:

```tut:book
import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext

val cachedThreadPool = Executors.newCachedThreadPool()
val BlockingFileIO   = ExecutionContext.fromExecutor(cachedThreadPool)
implicit val Main = ExecutionContext.global

val ioa: IO[Unit] =
  for {
    _     <- IO(println("Enter your name: "))
    _     <- IO.shift(BlockingFileIO)
    name  <- IO(scala.io.StdIn.readLine())
    _     <- IO.shift
    _     <- IO(println(s"Welcome $name!"))
    _     <- IO(cachedThreadPool.shutdown())
  } yield ()
```

We start by asking the user to enter its name and next we thread-shift to the `BlockingFileIO` execution context because we expect the following action to block on the thread for a long time and we don't want that to happen in the main thread of execution. After the `expensive IO operation` (readLine) gets back with a response we thread-shift back to the main execution context defined as an implicit value, and finally the program ends by showing a message in the console and shutting down a thread pool, all actions run in the main execution context.

Another somewhat less common application of `shift` is to reset the thread stack and yield control back to the underlying pool. For example:

```tut:book
lazy val doStuff = IO(println("stuff"))

lazy val repeat: IO[Unit] =
  for {
    _ <- doStuff
    _ <- IO.shift
    _ <- repeat
} yield ()
```

In this example, `repeat` is a very long running `IO` (infinite, in fact!) which will just hog the underlying thread resource for as long as it continues running.  This can be a bit of a problem, and so we inject the `IO.shift` which yields control back to the underlying thread pool, giving it a chance to reschedule things and provide better fairness. This shifting also "bounces" the thread stack, popping all the way back to the thread pool and effectively trampolining the remainder of the computation. Although the thread-shifting is not completely necessary, it might help in some cases to aliviate the use of the main thread pool.

Thus, this function has four important use cases:
- Shifting blocking actions off of the main compute pool.
- Defensively re-shifting asynchronous continuations back to the main compute pool.
- Yielding control to some underlying pool for fairness reasons.

`IO` is trampolined for all `synchronous` and `asynchronous` joins. This means that you can safely call `flatMap` in a recursive function of arbitrary depth, without fear of blowing the stack. So you can do this for example:

```tut:book
def signal[A](a: A): IO[A] = IO.async(_(Right(a)))

def loop(n: Int): IO[Int] =
  signal(n).flatMap { x =>
    if (x > 0) loop(n - 1) else IO.pure(0)
  }
```

## Parallelism

Since the introduction of the [Parallel](https://github.com/typelevel/cats/blob/master/core/src/main/scala/cats/Parallel.scala) typeclasss in the Cats library and its `IO` instance, it became possible to execute two or more given `IO`s in parallel.

### parMapN

It has the potential to run an arbitrary number of `IO`s in parallel, as long as the `IO` values have asynchronous execution, and it allows you to apply a function to the result (as in `map`). It finishes processing when all the `IO`s are completed, either successfully or with a failure. For example:

```tut:book
import cats.syntax.all._

val ioA = IO.shift *> IO(println("Running ioA"))
val ioB = IO.shift *> IO(println("Running ioB"))
val ioC = IO.shift *> IO(println("Running ioC"))

val program = (ioA, ioB, ioC).parMapN { (_, _, _) => () }

program.unsafeRunSync()
```

If any of the `IO`s completes with a failure then the result of the whole computation will be failed but not until all the `IO`s are completed. Example:

```tut:nofail
val a = IO.shift *> (IO.raiseError[Unit](new Exception("boom")) <* IO(println("Running ioA")))
val b = IO.shift *> IO(println("Running ioB"))

val parFailure = (a, b).parMapN { (_, _) => () }

parFailure.unsafeRunSync()
```

If one of the tasks fails immediately, then the other gets canceled and the computation completes immediately, so in this example the pairing via `parMapN` will not wait for 10 seconds before emitting the error:

```tut:silent
val ioA = Timer[IO].sleep(10.seconds) *> IO(println("Delayed!"))
val ioB = IO.shift *> IO.raiseError(new Exception("dummy"))

(ioA, ioB).parMapN((_, _) => ())
```

Note that the following example **will not run in parallel** because it's missing the asynchronous execution:

```tut:book
val c = IO(println("Hey C!"))
val d = IO(println("Hey D!"))

val nonParallel = (c, d).parMapN { (_, _) => () }

nonParallel.unsafeRunSync()
```

With `IO` thread forking or call-stack shifting has to be explicit. This goes for `parMapN` and for `start` as well. If scheduling fairness is a concern, then asynchronous boundaries have to be explicit.

## Concurrency

There are two methods defined by the `Concurrent` typeclasss to help you achieve concurrency, namely `race` and `racePair`.

### race

Run two `IO` tasks concurrently, and return the first to finish, either in success or error. The loser of the race is canceled.

The two tasks are executed in parallel if asynchronous, the winner being the first that signals a result. As an example, this is how a `timeout` operation could be implemented in terms of `race`:

```tut:book:silent
import scala.concurrent.duration._

def timeoutTo[A](io: IO[A], after: FiniteDuration, fallback: IO[A])(implicit timer: Timer[IO]): IO[A] = {
  IO.race(io, timer.sleep(after)).flatMap {
    case Left(a)  => IO.pure(a)
    case Right(_) => fallback
  }
}

def timeout[A](io: IO[A], after: FiniteDuration)(implicit timer: Timer[IO]): IO[A] = {
  timeoutTo(io, after, IO.raiseError(new Exception(s"Timeout after: $after")))
}
```

### racePair

Run two `IO` tasks concurrently, and returns a pair containing both the winner's successful value and the loser represented as a still-unfinished task.

If the first task completes in error, then the result will complete in error, the other task being canceled. On usage the user has the option of cancelling the losing task, this being equivalent with plain `race`:

```tut:book:silent
def racing[A, B](ioA: IO[A], ioB: IO[B]) =
  IO.racePair(ioA, ioB).flatMap {
    case Left((a, fiberB)) =>
       fiberB.cancel.map(_ => a)
    case Right((fiberA, b)) =>
      fiberA.cancel.map(_ => b)
  }
```

## "Unsafe" Operations

Pretty much we have been using some "unsafe" operations in the previous examples but we never explained any of them, so here it goes. All of the operations prefixed with `unsafe` are impure functions and perform side effects (for example Haskell has `unsafePerformIO`). But don't be scared by the name! You should write your programs in a monadic way using functions such as `map` and `flatMap` to compose other functions and ideally you should just call one of these unsafe operations only **once**, at the very end of your program.

### unsafeRunSync

Produces the result by running the encapsulated effects as impure side effects.

If any component of the computation is asynchronous, the current thread will block awaiting the results of the async computation. On JavaScript, an exception will be thrown instead to avoid generating a deadlock. By default, this blocking will be unbounded. To limit the thread block to some fixed time, use `unsafeRunTimed` instead.

Any exceptions raised within the effect will be re-thrown during evaluation.

```tut:book
IO(println("Sync!")).unsafeRunSync()
```

### unsafeRunAsync

Passes the result of the encapsulated effects to the given callback by running them as impure side effects.

Any exceptions raised within the effect will be passed to the callback in the `Either`. The callback will be invoked at most *once*. Note that it is very possible to construct an `IO` which never returns while still never blocking a thread, and attempting to evaluate that `IO` with this method will result in a situation where the callback is *never* invoked.

```tut:book
IO(println("Async!")).unsafeRunAsync(_ => ())
```

### unsafeRunCancelable

Evaluates the source `IO`, passing the result of the encapsulated effects to the given callback. Note that this has the potential to be interrupted.

```tut:book
IO(println("Potentially cancelable!")).unsafeRunCancelable(_ => ())
```

### unsafeRunTimed

Similar to `unsafeRunSync`, except with a bounded blocking duration when awaiting asynchronous results.

Please note that the `limit` parameter does not limit the time of the total computation, but rather acts as an upper bound on any *individual* asynchronous block.  Thus, if you pass a limit of `5 seconds` to an `IO` consisting solely of synchronous actions, the evaluation may take considerably longer than 5 seconds!

Furthermore, if you pass a limit of `5 seconds` to an `IO` consisting of several asynchronous actions joined together, evaluation may take up to `n * 5 seconds`, where `n` is the number of joined async actions.

As soon as an async blocking limit is hit, evaluation "immediately" aborts and `None` is returned.

Please note that this function is intended for **testing** purposes; it should never appear in your mainline production code!  It is absolutely not an appropriate function to use if you want to implement timeouts, or anything similar. If you need that sort of functionality, you should be using a streaming library (like [fs2](https://github.com/functional-streams-for-scala/fs2) or [Monix](https://monix.io/)).

```tut:book
import scala.concurrent.duration._

IO(println("Timed!")).unsafeRunTimed(5.seconds)
```

### unsafeToFuture

Evaluates the effect and produces the result in a `Future`.

This is similar to `unsafeRunAsync` in that it evaluates the `IO` as a side effect in a non-blocking fashion, but uses a `Future` rather than an explicit callback.  This function should really only be used if interoperating with legacy code which uses Scala futures.

```tut:book
IO("Gimme a Future!").unsafeToFuture()
```

