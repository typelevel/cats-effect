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

`IO` implements all the typeclasses shown in the hierarchy and it adds some extra functionality as it is described below.

## Unsafe Operations

All of the operations prefixed with `unsafe` are impure functions and perform side effects. But don't be scared by the name! You should write your programs in a monadic way using functions such as `map` and `flatMap` to compose other functions and ideally you should just call one of these unsafe operations only **once**, at the very end of your program.

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

## Additional Operations

### attempt

Materializes any sequenced exceptions into value space, where they may be handled. This is analogous to the `catch` clause in `try`/`catch`, being the inverse of `IO.raiseError`. Example:

```tut:book
IO.raiseError(new Exception("boom")).attempt.unsafeRunSync()
```

Note that this is provided by `IO`'s `MonadError` instance or more specifically from the `ApplicativeError` typeclass. So it can also be used when abstracting over the effect `F[_]`.

### shift

Note there are 2 overloads of the `IO.shift` function:
- One that takes an `Timer` that manages the thread-pool used to trigger async boundaries.
- Another that takes a Scala `ExecutionContext` as the thread-pool.

***Please use the former by default, use the later for fine grained control over the thread pool used.***

Examples:

There should be an implicit instance of `Timer[IO]` available to manage the thread-pools. By default, `Cats Effect` provides one for `IO`:

```tut:book
import cats.effect.Timer
import scala.concurrent.ExecutionContext.Implicits.global

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

In this example, `repeat` is a very long running `IO` (infinite, in fact!) which will just hog the underlying thread resource for as long as it continues running.  This can be a bit of a problem, and so we inject the `IO.shift` which yields control back to the underlying thread pool, giving it a chance to reschedule things and provide better fairness. This shifting also "bounces" the thread stack, popping all the way back to the thread pool and effectively trampolining the remainder of the computation. This sort of manual trampolining is unnecessary if `doStuff` is defined using `suspend` or `apply`, but if it was defined using `async` and does "not" involve any real concurrency, the call to `shift` will be necessary to avoid a `StackOverflowError`.

Thus, this function has four important use cases:
- Shifting blocking actions off of the main compute pool.
- Defensively re-shifting asynchronous continuations back to the main compute pool.
- Yielding control to some underlying pool for fairness reasons.
- Preventing an overflow of the call stack in the case of improperly constructed `async` actions.

`IO` is trampolined for all `synchronous` joins. This means that you can safely call `flatMap` in a recursive function of arbitrary depth, without fear of blowing the stack.
