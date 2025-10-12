---
id: io
title: What is IO?
---

## Introduction

`IO` is the fundamental building block of Cats Effect. It represents a **description** of a computation that may perform side effects, such as reading from a file, making a network request, or printing to the console. Importantly, `IO` is not the computation itself—it's a blueprint that describes what should happen when the computation is eventually executed.

Think of `IO` like a recipe: the recipe itself doesn't cook the meal, but it tells you exactly what steps to follow when you're ready to cook.

## Why IO?

In traditional imperative programming, side effects happen immediately:

```scala
// This prints immediately when the line is executed
println("Hello, World!")
```

This immediate execution makes programs hard to:
- **Test** (you can't easily mock side effects)
- **Compose** (side effects don't compose well)
- **Reason about** (effects happen in unpredictable order)
- **Cancel** (once started, effects are hard to stop)

`IO` solves these problems by making side effects **lazy** and **composable**:

```scala mdoc:silent
import cats.effect.IO
import cats.effect.unsafe.implicits.global

// This creates a description of printing, but doesn't print yet
val program: IO[Unit] = IO.println("Hello, World!")

// The printing only happens when we explicitly run the program
program.unsafeRunSync()  // Now it prints!
```

## Core Concepts

### 1. IO Describes, Doesn't Execute

`IO` values are **pure** - they don't perform side effects when created. They only describe what should happen:

```scala mdoc:silent
val readFile: IO[String] = IO.pure("file content")
val writeFile: IO[Unit] = IO.println("Writing to file")

// Neither of these actually reads or writes files yet!
// They're just descriptions waiting to be executed
```

### 2. IO is Composable

You can combine `IO` values to create more complex programs:

```scala mdoc:silent
val fileProgram: IO[String] = for {
  content <- IO.pure("input content")
  processed = content.toUpperCase
  _ <- IO.println("Writing to file")
} yield processed

// This creates a description of a complete program
// Nothing executes until we run it
```

### 3. IO Handles Errors Gracefully

`IO` has built-in error handling that's both safe and composable:

```scala mdoc:silent
val riskyOperation: IO[String] = IO.raiseError(new RuntimeException("Something went wrong!"))

val safeProgram: IO[String] = riskyOperation.handleErrorWith { error =>
  IO.pure(s"Recovered from: ${error.getMessage}")
}

// Errors are handled without throwing exceptions
```

### 4. IO Supports Concurrency

`IO` makes it easy to run computations concurrently:

```scala mdoc:silent
// Define simple data types for the example
case class User(id: Long, name: String)
case class Post(id: Long, title: String, userId: Long)

// Mock HTTP client
object httpClient {
  def getUser(id: Long) = scala.concurrent.Future.successful(User(id, "John Doe"))
  def getPosts(id: Long) = scala.concurrent.Future.successful(List(Post(1, "Post 1", id)))
}

val id = 123L
val fetchUser: IO[User] = IO.fromFuture(IO.pure(httpClient.getUser(id)))
val fetchPosts: IO[List[Post]] = IO.fromFuture(IO.pure(httpClient.getPosts(id)))

val parallelProgram: IO[(User, List[Post])] = (fetchUser, fetchPosts).parTupled

// Both operations run concurrently and safely
```

## Common Patterns

### Creating IO Values

```scala mdoc:silent
// From a pure value
val pureValue: IO[String] = IO.pure("Hello")

// From a side effect
val sideEffect: IO[Unit] = IO.println("Hello, World!")

// From a potentially failing operation
val randomOperation: IO[String] = IO.delay {
  if (scala.util.Random.nextBoolean()) "Success"
  else throw new RuntimeException("Random failure")
}
```

### Transforming IO Values

```scala mdoc:silent
val greetingProgram: IO[String] = for {
  name <- IO.pure("Alice")
  greeting = s"Hello, $name!"
  _ <- IO.println(greeting)
} yield greeting

// Or using map and flatMap
val program2: IO[String] = IO.pure("Alice")
  .map(name => s"Hello, $name!")
  .flatMap(greeting => IO.println(greeting).as(greeting))
```

### Error Handling

```scala mdoc:silent
val riskyProgram: IO[String] = IO.raiseError(new RuntimeException("Something went wrong"))

val safeProgram2: IO[String] = riskyProgram.handleErrorWith { error =>
  IO.pure(s"Recovered from: ${error.getMessage}")
}

// Or using attempt to convert to Either
val attemptedProgram: IO[Either[Throwable, String]] = riskyProgram.attempt
```

### Resource Management

```scala mdoc:silent
import cats.effect.Resource

val resourceProgram: Resource[IO, String] = 
  Resource.make(
    IO.println("Acquiring resource") *> IO.pure("my-resource")
  )(_ => IO.println("Releasing resource"))

// Use the resource safely
val resourceUsage: IO[String] = resourceProgram.use { resource =>
  IO.println(s"Using $resource") *> IO.pure(resource)
}
```

## IOApp

`IOApp` is the recommended way to structure Cats Effect applications:

```scala mdoc:silent
import cats.effect.{IO, IOApp}

object MyApp extends IOApp.Simple {
  val run = IO.println("Hello from IOApp!")
}
```

## Summary

`IO` is a powerful abstraction that:

- **Describes** computations without executing them
- **Composes** safely with other `IO` values
- **Handles** errors gracefully
- **Supports** concurrency and cancellation
- **Manages** resources safely

This makes it ideal for building robust, testable, and maintainable applications. For more advanced patterns and best practices, see the Best Practices guide.
