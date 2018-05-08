---
layout: docsplus
title:  "IO"
number: 9
source: "shared/src/main/scala/cats/effect/IO.scala"
scaladoc: "#cats.effect.IO"
---

A data type for encoding side effects as pure values, capable of
expressing both synchronous and asynchronous computations.

<nav role="navigation" id="toc"></nav>

## Introduction

A value of type `IO[A]` is a computation which, when evaluated, can
perform effects before returning a value of type `A`.

`IO` values are pure, immutable values and thus preserves referential
transparency, being usable in functional programming. An `IO` is a
data structure that represents just a description of a side effectful
computation.

`IO` can describe synchronous or asynchronous computations that:

1. on evaluation yield exactly one result
2. can end in either success or failure and in case of failure
   `flatMap` chains get short-circuited (`IO` implementing the algebra
   of `MonadError`)
3. can be canceled, but note this capability relies on the
   user to provide cancelation logic

Effects described via this abstraction are not evaluated until the
"end of the world", which is to say, when one of the "unsafe" methods
are used. Effectful results are not memoized, meaning that memory
overhead is minimal (and no leaks), and also that a single effect may
be run multiple times in a referentially-transparent manner. For
example:

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

The above example prints "hey!" twice, as the effect re-runs each time
it is sequenced in the monadic chain.

### On Referential Transparency and Lazy Evaluation

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

This doesn't work with `Future`, but works with `IO` and this ability is essential for _functional programming_.

### Stack Safety

`IO` is trampolined in its `flatMap` evaluation. This means that you
can safely call `flatMap` in a recursive function of arbitrary depth,
without fear of blowing the stack:

```tut:silent
def fib(n: Int, a: Long = 0, b: Long = 1): IO[Long] =
  IO(a + b).flatMap { b2 =>
    if (n > 0) 
      fib(n - 1, b, b2)
    else 
      IO.pure(b2)
  }
```

`IO` implements all the typeclasses shown in the [hierarchy](../typeclasses/). Therefore
all those operations are available for `IO`, in addition to some
others.

## Describing Effects

`IO` is a potent abstraction that can efficiently describe multiple
kinds of effects:

### Pure Values — IO.pure & IO.unit

You can lift pure values into `IO`, yielding `IO` values that are
"already evaluated", the following function being defined on IO's 
companion:

```tut:book
def pure[A](a: A): IO[A] = ???
```

Note that the given parameter is passed by value, not by name.

For example we can lift a number (pure value) into `IO` and compose it
with another `IO` that wraps a side a effect in a safe manner, as
nothing is going to be executed:

```tut:book
IO.pure(25).flatMap(n => IO(println(s"Number is: $n")))
```

It should be obvious that `IO.pure` cannot suspend side effects, because
`IO.pure` is eagerly evaluated, with the given parameter being passed
by value, so don't do this:

```tut:book
IO.pure(println("THIS IS WRONG!"))
```

In this case the `println` will trigger a side effect that is not
suspended in `IO` and given this code that probably is not our
intention.

`IO.unit` is simply an alias for `IO.pure(())`, being a reusable
reference that you can use when an `IO[Unit]` value is required, but
you don't need to trigger any other side effects:

```tut:silent
val unit: IO[Unit] = IO.pure(())
```

Given `IO[Unit]` is so prevalent in Scala code, the `Unit` type itself
being meant to signal completion of side effectful routines, this
proves useful as a shortcut and as an optimization, since the same
reference is returned.

### Synchronous Effects — IO.apply

It's probably the most used builder and the equivalent of
`Sync[IO].delay`, describing `IO` operations that can be evaluated
immediately, on the current thread and call-stack:

```tut:silent
def apply[A](body: => A): IO[A] = ???
```

Note the given parameter is passed ''by name'', its execution being
"suspended" in the `IO` context.

An example would be reading / writing from / to the console, which on
top of the JVM uses blocking I/O, so their execution is immediate:

```tut:silent
def putStrlLn(value: String) = IO(println(value))
val readLn = IO(scala.io.StdIn.readLine)
```

And then we can use that to model interactions with the console in a
purely functional way:

```tut:silent
for {
  _ <- putStrlLn("What's your name?")
  n <- readLn
  _ <- putStrlLn(s"Hello, $n!")
} yield ()
```

### Asynchronous Effects — IO.async & IO.cancelable

`IO` can describe asynchronous processes via the `IO.async` and
`IO.cancelable` builders.

`IO.async` is the operation that complies with the laws of
`Async#async` (see [Async](../typeclasses/async.html)) and can
describe simple asynchronous processes that cannot be canceled,
its signature being:

```tut:silent
def async[A](k: (Either[Throwable, A] => Unit) => Unit): IO[A] = ???
```

The provided registration function injects a callback that you can use
to signal either successful results (with `Right(a)`), or failures
(with `Left(error)`). Users can trigger whatever asynchronous side
effects are required, then use the injected callback to signal
completion.

For example, you don't need to convert Scala's `Future`, because you
already have a conversion operation defined in `IO.fromFuture`,
however the code for converting a `Future` would be straightforward:

```tut:silent
import scala.concurrent.{Future, ExecutionContext}
import scala.util.{Success, Failure}

def convert[A](fa: => Future[A])(implicit ec: ExecutionContext): IO[A] =
  IO.async { cb =>
    // This triggers evaluation of the by-name param and of onComplete, 
    // so it's OK to have side effects in this callback
    fa.onComplete {
      case Success(a) => cb(Right(a))
      case Failure(e) => cb(Left(e))
    }
  }
```

#### Cancelable Processes

For building cancelable `IO` tasks you need to use the
`IO.cancelable` builder, this being compliant with
`Concurrent#cancelable` (see [Concurrent](../typeclasses/concurrent.html))) 
and has this signature:

```tut:silent
def cancelable[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): IO[A] = ???
```

So it is similar with `IO.async`, but in that registration function
the user is expected to provide an `IO[Unit]` that captures the
required cancelation logic.

N.B. cancelation is the ability to interrupt an `IO` task before
completion, possibly releasing any acquired resources, useful in race
conditions to prevent leaks.

As example suppose we want to describe a `sleep` operation that
depends on Java's `ScheduledExecutorService`, delaying a tick for a
certain time duration:

```tut:silent
import java.util.concurrent.ScheduledExecutorService
import scala.concurrent.duration._

def delayedTick(d: FiniteDuration)
  (implicit sc: ScheduledExecutorService): IO[Unit] = {
 
  IO.cancelable { cb =>
    val r = new Runnable { def run() = cb(Right(())) }
    val f = sc.schedule(r, d.length, d.unit)
    
    // Returning the cancelation token needed to cancel 
    // the scheduling and release resources early
    IO(f.cancel(false))
  }
}
```

Note this delayed tick is already described by `IO.sleep` (via
`Timer`), so you don't need to do it.

More on dealing with ''cancelation'' below.

#### IO.never

`IO.never` represents a non-terminating `IO` defined in terms of
`async`, useful as shortcut and as a reusable reference:

```tut:silent
val never: IO[Nothing] = IO.async(_ => ())
```

This is useful in order to use non-termination in certain cases, like
race conditions. For example, given `IO.race`, we have these
equivalences:

```scala
IO.race(lh, IO.never) <-> lh.map(Left(_))

IO.race(IO.never, rh) <-> rh.map(Right(_))
```

It's also useful when dealing with the `onCancelRaiseError` operation.
Because cancelable `IO` values usually become non-terminating on
cancelation, you might want to use `IO.never` as the continuation of
an `onCancelRaiseError`.

See the description of `onCancelRaiseError`.

### Deferred Execution — IO.suspend

The `IO.suspend` builder has this equivalence:

```scala
IO.suspend(f) <-> IO(f).flatten
```

So it is useful for suspending effects, but that defers the completion
of the returned `IO` to some other reference. It's also useful for
modeling stack safe, tail recursive loops:

```tut:silent
def fib(n: Int, a: Long, b: Long): IO[Long] =
  IO.suspend {
    if (n > 0)
      fib(n - 1, b, a + b)
    else
      IO.pure(a)
  }
```

Normally a function like this would eventually yield a stack overflow
error on top of the JVM. By using `IO.suspend` and doing all of those
cycles using `IO`'s run-loop, its evaluation is lazy and it's going to
use constant memory. This would work with `flatMap` as well, of
course, `suspend` being just nicer in this example.

We could describe this function using Scala's `@tailrec` mechanism,
however by using `IO` we can also preserve fairness by inserting
asynchronous boundaries:

```tut:silent
import cats.implicits._
import cats.effect.Timer

def fib(n: Int, a: Long, b: Long)(implicit timer: Timer[IO]): IO[Long] =
  IO.suspend {
    if (n == 0) IO.pure(a) else {
      val next = fib(n - 1, b, a + b)
      // Every 100 cycles, introduce a logical thread fork
      if (n % 100 == 0)
        timer.shift *> next
      else
        next
    }
  }
```

And now we have something more interesting than a `@tailrec` loop. As
can be seen, `IO` allows very precise control over the evaluation.

## Concurrency and Cancelation

`IO` can describe interruptible asynchronous processes. As an
implementation detail:

1. not all `IO` tasks are cancelable, only tasks built with
   `IO.cancelable` can cancel the evaluation
  - should go without saying (after point 1) that `flatMap` chains are
    not auto-cancelable
  - if this is a problem, `flatMap` loops can be made cancelable by
    using `IO.cancelBoundary`    
2. `IO` tasks that are cancelable, usually become non-terminating on
   `cancel`   
  - such tasks can be turned into tasks that trigger an error on
    cancelation with `onCancelRaiseError`, which can be used for
    materializing cancelation and thus trigger necessary logic, the
    `bracket` operation being described in terms of
    `onCancelRaiseError`
    
Also this might be a point of confusion for folks coming from Java and
that expect the features of `Thread.interrupt` or of the old and
deprecated `Thread.stop`:

`IO` cancelation does NOT work like that, as thread interruption in
Java is inherently *unsafe, unreliable and not portable*!

### Building cancelable IO tasks

Cancelable `IO` tasks can be described via the `IO.cancelable`
builder. The `delayedTick` example making use of the Java's
`ScheduledExecutorService` was already given above, but to recap:

```tut:silent
def sleep(d: FiniteDuration)
  (implicit sc: ScheduledExecutorService): IO[Unit] = {

  IO.cancelable { cb => 
    val r = new Runnable { def run() = cb(Right(())) }
    val f = sc.schedule(r, d.length, d.unit)
    // Returning a function that can cancel our scheduling
    IO(f.cancel(false))
  }
}
```

N.B. if you don't specify cancelation logic for a task, then the task
is NOT cancelable. So for example, using Java's blocking I/O still:

```tut:silent
import java.io._
import scala.util.control.NonFatal

def unsafeFileToString(file: File) = {
  // Freaking Java :-)
  val in = new BufferedReader(
    new InputStreamReader(new FileInputStream(file), "utf-8"))
  
  try {
    // Uninterruptible loop
    val sb = new StringBuilder()
    var hasNext = true
    while (hasNext) {
      hasNext = false
      val line = in.readLine()
      if (line != null) {
        hasNext = true
        sb.append(line)
      }
    }
    sb.toString
  } finally {
    in.close()
  }
}

def readFile(file: File)(implicit ec: ExecutionContext) =
  IO.async[String] { cb =>
    ec.execute(() => {
      try {
        // Signal completion
        cb(Right(unsafeFileToString(file)))
      } catch {
        case NonFatal(e) =>
          cb(Left(e))
      }
    })
  }
```

This is obviously not cancelable and there's no magic that the `IO`
implementation does to make that loop cancelable. No, we are not going
to use Java's `Thread.interrupt`, because that would be unsafe and
unreliable and besides, whatever the `IO` does has to be portable
between platforms.

But there's a lot of flexibility in what can be done, including here.
We could simply introduce a variable that changes to `false`, to be
observed in that `while` loop:

```tut:silent
import java.util.concurrent.atomic.AtomicBoolean

def unsafeFileToString(file: File, isActive: AtomicBoolean) = {
  val sc = new StringBuilder
  // ...
  var hasNext = true
  while (hasNext && isActive.get) { 
    ??? 
  }
  sc.toString
}

def readFile(file: File)(implicit ec: ExecutionContext) =
  IO.cancelable[String] { cb =>
    val isActive = new AtomicBoolean(true)
    
    ec.execute(() => {
      try {
        // Signal completion
        cb(Right(unsafeFileToString(file, isActive)))
      } catch {
        case NonFatal(e) =>
          cb(Left(e))
      }
    })    
    // On cancel, signal it
    IO(isActive.set(false))
  }
```

#### Gotcha: Cancelation is a Concurrent Action!

This is not always obvious, not from the above examples, but you might
be tempted to do something like this:

```tut:silent
def readLine(in: BufferedReader)(implicit ec: ExecutionContext) =
  IO.cancelable[String] { cb =>
    ec.execute(() => cb(
      try Right(in.readLine()) 
      catch { case NonFatal(e) => Left(e) }))
      
    // Cancelation logic is not thread-safe!
    IO(in.close())
  }
```

An operation like this might be useful in streaming abstractions that
stream I/O chunks via `IO` (via libraries like FS2, Monix, or others).

But the described operation is incorrect, because `in.close()` is
*concurrent* with `in.readLine`, which can lead to thrown exceptions
and in many cases it can lead to data *corruption*. This is a big
no-no. We want to interrupt whatever it is that the `IO` is doing, but
not at the cost of data corruption.

Therefore the user needs to handle thread safety concerns. So here's
one way of doing it:

```tut:silent
def readLine(in: BufferedReader)(implicit ec: ExecutionContext) =
  IO.cancelable[String] { cb =>
    val isActive = new AtomicBoolean(true)
    ec.execute { () => 
      if (isActive.getAndSet(false)) {
        try cb(Right(in.readLine()))
        catch { case NonFatal(e) => cb(Left(e)) }
      }
      // Note there's no else; if cancelation was executed
      // then we don't call the callback; task becoming 
      // non-terminating ;-)
    }
    // Cancelation logic
    IO {
      // Thread-safe gate
      if (isActive.getAndSet(false))
        in.close()
    }
  }
```

In this example it is the cancelation logic itself that calls
`in.close()`, but the call is safe due to the thread-safe guard that
we're creating by usage of an atomic `getAndSet`.

This is using an `AtomicBoolean` for thread-safety, but don't shy away
from using intrinsic locks / mutexes via `synchronize` blocks or
whatever else concurrency primitives the JVM provides, whatever is
needed in these side effectful functions. And don't worry, this is
usually needed only in `IO.cancelable`, `IO.async` or `IO.apply`, as
these builders represents the FFI for interacting with the impure
world, aka the dark side, otherwise once you're in `IO`'s context, you
can compose concurrent tasks using higher level tools.

Shared memory concurrency is unfortunately both the blessing and the
curse of working with kernel threads. Not a big problem on N:1
platforms like JavaScript, but there you don't get in-process CPU
parallelism either. Such is life, a big trail of tradeoffs.

### Concurrent start + cancel

You can use `IO` as a green-threads system, with the "fork" operation
being available via `IO#start`, the operation that's compliant with
`Concurrent#start`. This is a method with the following signature:

```scala
def start: IO[Fiber[IO, A]]
```

Returned is a [Fiber](./fiber.html). You can think of fibers as being
lightweight threads, a fiber being the pure and light equivalent of a
thread that can be either joined (via `join`) or interrupted (via
`cancel`).

Example:

```tut:silent
// Needed in order to get a Timer[IO], for IO.shift below
import scala.concurrent.ExecutionContext.Implicits.global

val launchMissiles = IO.raiseError(new Exception("boom!"))
val runToBunker = IO(println("To the bunker!!!"))

for {
  fiber <- IO.shift *> launchMissiles.start
  _ <- runToBunker.handleErrorWith { error =>
    // Retreat failed, cancel launch (maybe we should
    // have retreated to our bunker before the launch?)
    fiber.cancel *> IO.raiseError(error)
  }
  aftermath <- fiber.join
} yield {
  aftermath
}
```

Implementation notes:

- `start` does NOT fork automatically, only asynchronous `IO` values
  will actually execute concurrently via `start`, so in order to
  ensure parallel execution for synchronous actions, then you can use
  `IO.shift` (remember, with `IO` such behavior is always explicit!)
- the `*>` operator is defined in Cats and you can treat it as an
  alias for `lh.flatMap(_ => rh)`
  
### runCancelable & unsafeRunCancelable

The above is the pure `cancel`, accessible via `Fiber`. However the
second way to access cancelation and thus interrupt tasks is via
`runCancelable` (the pure version) and `unsafeRunCancelable` (the
unsafe version).

Example relying on the side-effecting `unsafeRunCancelable` and note
this kind of code is impure and should be used with care:

```tut:silent
// Delayed println
val io: IO[Unit] = IO.sleep(10.seconds) *> IO(println("Hello!"))

val cancel: () => Unit = 
  io.unsafeRunCancelable(r => println(s"Done: $r"))

// ... if a race condition happens, we can cancel it,
// thus canceling the scheduling of `IO.sleep`
cancel()
```

The `runCancelable` alternative is the operation that's compliant with
the laws of [ConcurrentEffect](../typeclasses/concurrent-effect.html).
Same idea, only the actual execution is suspended in `IO`:

```tut:silent
val pureResult: IO[IO[Unit]] = io.runCancelable { r => 
  IO(println(s"Done: $r"))
}

// On evaluation, this will first execute the source, then it 
// will cancel it, because it makes perfect sense :-)
val cancel = pureResult.flatten
```

### uncancelable marker

Given a cancelable `IO`, we can turn it into an `IO` that cannot be
canceled:

```tut:silent
// Our reference from above
val io: IO[Unit] = IO.sleep(10.seconds) *> IO(println("Hello!"))

// This IO can't be canceled, even if we try
io.uncancelable
```

Sometimes you need to ensure that an `IO`'s execution is *atomic*, or
in other words, either all of it executes, or none of it. And this is
what this operation does — cancelable IOs are by definition not atomic
and in certain cases we need to make them atomic.

This law is compliant with the laws of `Concurrent#uncancelable` (see
[Concurrent](../typeclasses/concurrent.html)).

### Materialization of interruption via onCancelRaiseError

`IO` tasks usually become non-terminating when canceled, creating
problems in case you want to release resources.

Just like `attempt` (from `MonadError`) can materialize thrown errors,
we have `onCancelRaiseError` that can materialize cancelation.  This
way we can detect cancelation and trigger specific logic in response.

As example, this is more or less how a `bracket` operation can get
implemented for `IO`:


```tut:silent
import cats.syntax.all._
import scala.concurrent.CancellationException

val wasCanceled = new CancellationException("nope")
val ioa: IO[Int] = IO(???)

ioa.onCancelRaiseError(wasCanceled).handleErrorWith {
  case `wasCanceled` =>
    IO(println("Was canceled!")) *> IO.never
  case e =>
    IO.raiseError(e)
}
```

### IO.cancelBoundary

Returns a cancelable boundary — an `IO` task that checks for the
cancelation status of the run-loop and does not allow for the bind
continuation to keep executing in case cancelation happened.

This operation is very similar to `IO.shift`, as it can be dropped in
`flatMap` chains in order to make such long loops cancelable:

```tut:silent
def fib(n: Int, a: Long, b: Long): IO[Long] =
  IO.suspend {
    if (n <= 0) IO.pure(a) else {
      val next = fib(n - 1, b, a + b)

      // Every 100-th cycle check cancellation status
      if (n % 100 == 0)
        IO.cancelBoundary *> next
      else
        next
    }
  }
```

Again mentioning this for posterity: with `IO` everything is explicit,
the protocol being easy to follow and predictable in a WYSIWYG
fashion.

### Race Conditions — race & racePair

A race condition is a piece of logic that creates a race between two
or more tasks, with the winner being signaled immediately, with the
losers being usually canceled.

`IO` provides two operations for races in its companion:

```scala
// simple version
def race[A, B](lh: IO[A], rh: IO[B]): IO[Either[A, B]]
  
// advanced version
def racePair[A, B](lh: IO[A], rh: IO[B]): IO[Either[(A, Fiber[IO, B]), (Fiber[IO, A], B)]]
```

The simple version, `IO.race`, will cancel the loser immediately,
whereas the second version gives you a [Fiber](./fiber.html), letting
you decide what to do next.

So `race` can be derived with `racePair` like so:

```tut:silent
def race[A, B](lh: IO[A], rh: IO[B]): IO[Either[A, B]] =
  IO.racePair(lh, rh).flatMap {
    case Left((a, fiber)) => 
      fiber.cancel.map(_ => Left(a))
    case Right((fiber, b)) => 
      fiber.cancel.map(_ => Right(b))
  }
```

Using `race` we could implement a "timeout" operation:

```tut:silent
def timeoutTo[A](fa: IO[A], after: FiniteDuration, fallback: IO[A])
  (implicit timer: Timer[IO]): IO[A] = {

  IO.race(fa, timer.sleep(after)).flatMap {
    case Left(a) => IO.pure(a)
    case Right(_) => fallback
  }
}

def timeout[A](fa: IO[A], after: FiniteDuration)
  (implicit timer: Timer[IO]): IO[A] = {

  val error = new CancellationException(after.toString)
  timeoutTo(fa, after, IO.raiseError(error))
}
```

NOTE: like all things with `IO`, tasks are not forked automatically
and `race` won't do automatic forking either. So if the given tasks
are asynchronous, they could be executed in parallel, but if they
are not asynchronous (and thus have immediate execution), then
they won't run in parallel.

Always remember that IO's policy is WYSIWYG.

### Comparison with Haskell's "async interruption"

Haskell treats interruption with what they call "asynchronous
exceptions", providing the ability to interrupt a running task by 
throwing an exception from another thread (concurrently).

For `cats.effect`, for the "cancel" action, what happens is that
whatever you specify in the `IO.cancelable` builder gets executed. And
depending on the implementation of an `IO.cancelable` task, it can
become non-terminating. If we'd need to describe our `cancel`
operation with an impure signature, it would be:

```scala
() => Unit
```

By comparison Haskell (and possibly the upcoming Scalaz 8 `IO`), sends
an error, a `Throwable` on interruption and canceled tasks get
completed with that `Throwable`. Their impure cancel is:

```scala
Throwable => Unit
```

We on the other hand have an `onCancelRaiseError(e: Throwable)`, which
can transform a task that's non-terminating on cancel, into one that
raises an error on cancel. This operation also creates a race
condition, cutting off the signaling to downstream, even if the source
is not cancelable.

`Throwable => Unit` allows the task's logic to know the cancelation
reason, however cancelation is about cutting the connection to the
producer, closing all resources as soon as possible, because you're no
longer interested in the result, due to some race condition that
happened.

`Throwable => Unit` is also a little confusing, being too broad in
scope. Users might be tricked into sending messages back to the
producer via this channel, in order to steer it, to change its
outcome - however cancelation is cancelation, we're doing it for the
purpose of releasing resources and the implementation of race
conditions will end up closing the connection, disallowing the
canceled task to send anything downstream.

Therefore it's confusing for the user and the only practical use is to
release resources differently, based on the received error. But that's
not a use-case that's worth pursuing, given the increase in
complexity.

## Safe Resource Acquisition and Release

### Status Quo

In mainstream imperative languages you usually have `try / finally`
statements at disposal for acquisition and safe release of resources.
Pattern goes like this:

```tut:silent
import java.io._

def javaReadFirstLine(file: File): String = {
  val in = new BufferedReader(new FileReader(file))
  try {
    in.readLine()
  } finally {
    in.close()
  }
}
```

It does have problems like:

1. this statement is obviously meant for side-effectful computations
   and can't be used by FP abstractions
2. it's only meant for synchronous execution, so we can't use it
   when working with abstractions capable of asynchrony
   (e.g. `IO`, `Task`, `Future`)
3. `finally` executes irregardless of the exception type, 
   indiscriminately, so if you get an out of memory error it still
   tries to close the file handle, unnecessarily delaying a process
   crash
4. if the body of `try` throws an exception, then followed by
   the body of `finally` also throwing an exception, then the
   exception of `finally` gets rethrown, hiding the original problem
   
### bracket

Via the `bracket` operation we can easily describe the above:

```tut:silent
def readFirstLine(file: File): IO[String] =
  IO(new BufferedReader(new FileReader(file))).bracket { in =>
    // Usage (the try block)
    IO(in.readLine())
  } { in =>
    // Releasing the reader (the finally block)
    IO(in.close())
  }
```

Notes:

1. this is pure, so it can be used for FP
2. this works with asynchronous `IO` actions
3. the `release` action will happen regardless of the exit status 
   of the `use` action, so it will execute for successful completion,
   for thrown errors or for canceled execution
4. if the `use` action throws an error and then the `release` action
   throws an error as well, the reported error will be that of
   `use`, whereas the error thrown by `release` will just get logged
   (via `System.err`)   

Of special consideration is that `bracket` calls the `release` action
on cancelation as well. Consider this sample:

```tut:silent
def readFile(file: File): IO[String] = {
  // Opens file with an asynchronous boundary before it, 
  // ensuring that processing doesn't block the "current thread"
  val acquire = IO.shift *> IO(new BufferedReader(new FileReader(file)))
    
  acquire.bracket { in =>
    // Usage (the try block)
    IO {
      // Ugly, low-level Java code warning!
      val content = new StringBuilder()
      var line: String = null
      do {
        line = in.readLine()
        if (line != null) content.append(line)
      } while (line != null)
      content.toString()
    }
  } { in =>
    // Releasing the reader (the finally block)
    // This is problematic if the resulting `IO` can get 
    // canceled, because it can lead to data corruption
    IO(in.close())
  }
}
```

That loop can be slow, we could be talking about a big file and
as described in the "*Concurrency and Cancelation*" section,
cancelation is a concurrent action with whatever goes on in `use`.

And in this case, on top of the JVM that is capable of multi-threading, 
calling `io.close()` concurrently with that loop
can lead to data corruption. Depending on use-case synchronization
might be needed to prevent it: 

```tut:silent
def readFile(file: File): IO[String] = {
  // Opens file with an asynchronous boundary before it, 
  // ensuring that processing doesn't block the "current thread"
  val acquire = IO.shift *> IO(new BufferedReader(new FileReader(file)))
    
  // Suspended execution because we are going to mutate 
  // a shared variable
  IO.suspend {
    // Shared state meant to signal cancelation
    var isCanceled = false
    
    acquire.bracket { in =>
      IO {
        val content = new StringBuilder()
        var line: String = null
        do {
        // Synchronized access to isCanceled and to the reader
          line = in.synchronized {
            if (!isCanceled)
              in.readLine()
            else
              null
          }
          if (line != null) content.append(line)
        } while (line != null)
        content.toString()
      }
    } { in =>
      IO {
        // Synchronized access to isCanceled and to the reader
        in.synchronized {
          isCanceled = true
          in.close()
        }
      }
    }
  }
}
```

### bracketCase

The `bracketCase` operation is the generalized `bracket`, also receiving
an `ExitCase` in `release` in order to distinguish between:

1. successful completion
2. completion in error
3. cancelation

Usage sample:

```tut:silent
import cats.effect.ExitCase.{Completed, Error, Canceled}

def readLine(in: BufferedReader): IO[String] =
  IO.pure(in).bracketCase { in =>
    IO(in.readLine())
  } { 
    case (_, Completed | Error(_)) =>
      // Do nothing
      IO.unit
    case (in, Canceled(_)) =>
      IO(in.close())
  }
```

In this example we are only closing the passed resource in case
cancelation occurred. As to why we're doing this — consider that 
the `BufferedReader` reference was given to us and usually the 
producer of such a resource should also be in charge of releasing 
it. If this function would release the given `BufferedReader` on
a successful result, then this would be a flawed implementation.

Remember the age old C++ idiom of "_resource acquisition is 
initialization (RAII)_", which says that the lifetime of a resource
should be tied to the lifetime of its parent.

But in case we detect cancelation, we might want to close that 
resource, because in the case of a cancelation event, we might
not have a "run-loop" active after this `IO` returns its result,
so there might not be anybody available to release it.

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
val ioB = IO.shift *> IO.raiseError[Unit](new Exception("dummy"))

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

## Best Practices

This section presents some best practices for working with `IO`:

### Keep Granularity

It's better to keep the granularity, so please don't do something like this:

```scala
IO {
  readingFile
  writingToDatabase
  sendBytesOverTcp
  launchMissiles
}
```

In FP we embrace reasoning about our programs and since `IO` is a `Monad` you can compose bigger programs from small ones in a `for-comprehension`.
For example:

```scala
val program =
  for {
    data <- readFile
    _    <- writeToDatabase(data)
    _    <- sendBytesOverTcp(data)
    _    <- launchMissiles
  } yield ()
```

Each step of the comprehension is a small program, and the resulting `program` is a composition of all those small steps,
which is compositional with other programs. `IO` values compose.

### Use pure functions in map / flatMap

When using `map` or `flatMap` it is not recommended to pass a side effectful function, as mapping functions should also be pure.
So this should be avoided:

```tut:book
IO.pure(123).map(n => println(s"NOT RECOMMENDED! $n"))
```

This too should be avoided, because the side effect is not suspended in the returned `IO` value:

```tut:book
IO.pure(123).flatMap { n =>
  println(s"NOT RECOMMENDED! $n")
  IO.unit
}
```

The correct approach would be this:

```tut:book
IO.pure(123).flatMap { n =>
  // Properly suspending the side effect
  IO(println(s"RECOMMENDED! $n"))
}
```

Note that as far as the actual behavior of `IO` is concerned, something like `IO.pure(x).map(f)` is equivalent with `IO(f(x))` and `IO.pure(x).flatMap(f)` is equivalent with `IO.suspend(f(x))`.

But you should not rely on this behavior, because it is NOT described by the laws required by the `Sync` type class and those laws are the only guarantees of behavior that you get. For example the above equivalence might be broken in the future in regards to error handling. So this behavior is currently there for safety reasons, but you should regard it as an implementation detail that could change in the future.

Stick with pure functions.
