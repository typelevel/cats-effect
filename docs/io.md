---
id: io
title: Understanding IO
---

## Introduction

`IO` is the foundational data type in **Cats Effect**.  
If you're new to this library, we recommend first reading the [Getting Started](getting-started.md) guide.  
This page dives deeper into what `IO` is, why it exists, and how it enables pure, composable, and safe effectful programming.

At its core, `IO` represents a **description** of a computation that may perform side effects.  
It does **not** execute those effects immediately - it's a *blueprint* for what will happen when the program eventually runs.

---

## The Problem IO Solves

In most programming languages, side effects like printing or reading files execute as soon as the line of code runs:

```scala
println("Hello, World!")
```
This message prints as soon as the line executes.

This direct style can make programs hard to:

- **Test** — side effects happen immediately and unpredictably.
- **Compose** — you can't easily combine or sequence side effects safely.
- **Reason about** — order and timing of effects become unclear.
- **Cancel** — once started, you can't easily stop or manage running work.

Cats Effect's `IO` fixes this by making effects lazy (they only run when told to) and composable (you can chain and transform them safely).

## Core Principles

### 1. IO Describes, Not Executes

An `IO` value doesn't run when created- it's a description of what should happen.

```scala mdoc:silent
import cats.effect.IO

val readFile: IO[String] = IO.blocking {
  scala.io.Source.fromFile("example.txt").mkString
}

val writeFile: IO[Unit] = IO.blocking {
  scala.tools.nsc.io.File("output.txt").writeAll("Hello, World!")
}
```

**What happens:**

- Nothing runs yet! Both are just blueprints describing future actions.
- The file isn't opened or written to until you explicitly run the `IO`.

### 2. IO is Composable

You can combine multiple `IO`s together to form larger programs.

```scala mdoc:silent
val fileProcessingProgram: IO[String] = for {
  content <- readFile
  processed = content.toUpperCase
  _ <- writeFile
} yield processed
```

**What happens:**

- The program describes: read → process → write.
- Still nothing executes until the whole thing is run.

**Expected outcome:** when executed, the file is read, text is converted to uppercase, and then written.

### 3. IO Handles Errors Gracefully

`IO` lets you handle errors without using exceptions.

```scala mdoc:silent
val riskyFileRead: IO[String] = IO.delay {
  scala.io.Source.fromFile("nonexistent.txt").mkString
}

val safeFileRead: IO[String] = riskyFileRead.handleErrorWith { error =>
  IO.pure(s"Could not read file: ${error.getMessage}")
}
```

**What happens:**  
If the file doesn't exist, no exception crashes the program.  
Instead, the error is caught, and the fallback message is returned.

**Expected result:**  
`"Could not read file: nonexistent.txt (No such file or directory)"`

## Creating IO Values

### From Pure Values

```scala mdoc:silent
val pureValue: IO[String] = IO.pure("Hello, World!")
```

**Outcome:**  
Just wraps a pure value inside an `IO`. No side effect happens.

### From Side Effects

```scala mdoc:silent
val readConfig: IO[String] = IO.blocking {
  scala.io.Source.fromFile("config.txt").mkString
}

val printMessage: IO[Unit] = IO.println("Hello, World!")
```

**Explanation:**

- `IO.blocking` wraps blocking side effects (like file I/O) and runs them on a dedicated thread pool.
- `IO.println` is a safe helper for console output.

### From Asynchronous Operations

```scala mdoc:silent
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

val futureResult: Future[String] = Future {
  Thread.sleep(1000)
  "Async result"
}

val ioFromFuture: IO[String] = IO.fromFuture(IO.pure(futureResult))
```

**What happens:**  
Converts a `Future` into an `IO`.  
When run, it waits for 1 second and then returns `"Async result"`.

### From Callback-Based APIs

```scala mdoc:silent
def callbackApi(callback: Either[Throwable, String] => Unit): Unit = {
  new Thread(() => {
    Thread.sleep(100)
    callback(Right("Callback result"))
  }).start()
}

val ioFromCallback: IO[String] = IO.async_[String] { cb =>
  callbackApi(cb)
}
```

**Explanation:**  
This bridges old callback-style APIs with modern functional style.  
The result `"Callback result"` is wrapped in `IO`.

## Transforming IO Values

### Sequential Composition

You can chain operations one after another using for comprehensions.

```scala mdoc:silent
case class User(id: Int, name: String)
case class Profile(userId: Int, name: String)
case class Settings(userId: Int, theme: String)

def getUserFromDatabase(id: Int): User = User(id, "John")
def getProfile(userId: Int): Profile = Profile(userId, "John's Profile")
def getSettings(userId: Int): Settings = Settings(userId, "dark")

val sequentialProgram: IO[String] = for {
  user <- IO.blocking(getUserFromDatabase(1))
  profile <- IO.blocking(getProfile(user.id))
  settings <- IO.blocking(getSettings(user.id))
} yield s"${user.name} - ${profile.name} (${settings.theme})"
```

**Expected output:** `"John - John's Profile (dark)"`

### Parallel Composition

Run independent computations at the same time using `.parTupled`.

```scala mdoc:silent
val parallelProgram: IO[(User, Profile, Settings)] = (
  IO.blocking(getUserFromDatabase(1)),
  IO.blocking(getProfile(1)),
  IO.blocking(getSettings(1))
).parTupled
```

**Outcome:**  
All three run concurrently.  
When all finish, the results are combined into a tuple.

**Expected result:** `(User(1, "John"), Profile(1, "John's Profile"), Settings(1, "dark"))`

### Error Handling Patterns

```scala mdoc:silent
val robustProgram: IO[String] = riskyFileRead
  .handleErrorWith { e =>
    IO.println(s"Failed: ${e.getMessage}") *> IO.pure("Default content")
  }
  .flatMap { content =>
    if (content.isEmpty) IO.raiseError(new RuntimeException("Empty!"))
    else IO.pure(content)
  }
```

**Explanation:**  
If an error happens, a message is printed and a default value is used.  
If content is empty, it fails explicitly.

**Expected outcome:**  
Either prints an error and continues, or raises `"Empty!"`.

## Managing Resources Safely

Working with files, sockets, or database connections requires careful cleanup.  
If you forget to close them, your program may leak memory or lock files.

Cats Effect provides the `Resource` type for automatic and safe management.

### Manual Resource Management

With `Resource.make`, you define how to acquire and release a resource.

```scala mdoc:silent
import cats.effect.{IO, Resource}

val readerResource: Resource[IO, scala.io.Source] =
  Resource.fromAutoCloseable(IO.blocking(scala.io.Source.fromFile("example.txt")))

val readWithResource: IO[String] = readerResource.use { source =>
  IO.blocking(source.getLines().mkString("\n"))
}
```

**What happens:**

1. The file opens.
2. The first line is read.
3. Whether success or failure, the file is automatically closed.

**Expected outcome:** safely reads one line and closes the file.

### Automatic Resource Management

If a resource implements `AutoCloseable`, use `Resource.fromAutoCloseable` for simplicity.

```scala mdoc:silent
val autoResource: IO[String] =
  Resource.fromAutoCloseable(IO.blocking(scala.io.Source.fromFile("example.txt")))
    .use(source => IO.blocking(source.getLines().mkString("\n")))
```

**Outcome:** identical behavior — safe open, read, and close.

## Concurrency and Cancellation

Cats Effect supports lightweight concurrency using **fibers**, tiny threads managed by the runtime.  
They're cheap to create and can be safely cancelled or joined.

### Starting and Joining Fibers

```scala mdoc:silent
import scala.concurrent.duration._

val fiberExample: IO[String] = for {
  fiber <- IO.println("Task running...").start
  _ <- IO.println("Main thread continues...")
  _ <- fiber.join.void
} yield "Task completed"
```

**Explanation:**  
Starts a new fiber that prints a message.  
Meanwhile, the main fiber prints immediately and then waits for the task to complete.

**Expected output:**
```
Main thread continues...
Task running...
```

### Cancelling a Fiber

```scala mdoc:silent
val cancellable: IO[Unit] = for {
  fiber <- IO.sleep(5.seconds).start
  _ <- IO.sleep(1.second)
  _ <- IO.println("Cancelling...")
  _ <- fiber.cancel
} yield ()
```

**What happens:**  
After 1 second, the task is cancelled — the 5-second sleep never finishes.

**Expected output:**
```
Cancelling...
```

### Racing Two Computations

```scala mdoc:silent
val fast = IO.sleep(100.millis) *> IO.pure("fast")
val slow = IO.sleep(1.second) *> IO.pure("slow")

val raceResult: IO[String] = IO.race(fast, slow).map(_.fold(identity, identity))
```

**Outcome:**  
Both start together.  
`fast` finishes first → `"fast"` is returned, and `slow` is cancelled.

**Expected result:** `"fast"`

## Advanced Patterns

### Retrying with Backoff

Useful when dealing with temporary failures (like flaky networks).

```scala mdoc:silent
import scala.concurrent.duration._

def retryWithBackoff[A](
  io: IO[A],
  retries: Int = 3,
  delay: FiniteDuration = 100.millis
): IO[A] =
  io.handleErrorWith { e =>
    if (retries > 0)
      IO.println(s"Retrying in ${delay.toMillis}ms...") *> IO.sleep(delay) *> retryWithBackoff(io, retries - 1, delay * 2)
    else IO.raiseError(e)
  }
```

**Expected outcome:**  
If the operation keeps failing, it retries with increasing delays — 100ms, 200ms, 400ms, etc.

### Adding a Timeout

```scala mdoc:silent
def withTimeout[A](io: IO[A], timeout: FiniteDuration): IO[A] =
  IO.race(io, IO.sleep(timeout)).flatMap {
    case Left(value) => IO.pure(value)
    case Right(_)    => IO.raiseError(new RuntimeException("Operation timed out"))
  }
```

**Explanation:**  
Runs your computation and a timer together — whichever finishes first wins.

**Example:**  
If `io` sleeps for 5s but `timeout` is 2s → raises `"Operation timed out"`.

## Best Practices

### Wrap Side Effects Safely

```scala
val safeIO = IO.blocking(scala.io.Source.fromFile("file.txt").mkString)
val unsafeIO = IO.pure(scala.io.Source.fromFile("file.txt").mkString) // executes immediately!
```

### Handle Errors Gracefully

```scala
val safe = riskyFileRead.handleErrorWith(_ => IO.pure("Default value"))
```

### Use Resource for Cleanup

```scala
val safeRead = Resource
  .fromAutoCloseable(IO.blocking(scala.io.Source.fromFile("file.txt")))
  .use(source => IO.blocking(source.mkString))
```

### Compose Declaratively

```scala
val composed: IO[String] = for {
  data <- readFile
  processed <- IO.delay(data.reverse)
  _ <- writeFile
} yield processed
```

**Expected outcome:**  
Reads data, processes it, and writes back — safely and clearly.

## Summary

`IO` lets you write functional, testable, and safe Scala code by:

- **Describing** side effects instead of running them
- **Composing** computations cleanly
- **Managing** resources automatically
- **Handling** errors and concurrency safely

**With `IO`, your whole program behaves like a pure function — predictable, testable, and reliable, even when it performs real-world effects.**

For deeper topics, explore [Learning Resources](third-party-resources.md) with Books, Videos and Courses