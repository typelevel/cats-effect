---
id: getting-started
title: Getting Started
---

## Quick Start

Add the following to your **build.sbt**:

```scala
libraryDependencies += "org.typelevel" %% "cats-effect" % "3.6.3"
```

Naturally, if you're using ScalaJS, you should replace the double `%%` with a triple `%%%`. If you're on Scala 2, it is *highly* recommended that you enable the [better-monadic-for](https://github.com/oleg-py/better-monadic-for) plugin, which fixes a number of surprising elements of the `for`-comprehension syntax in the Scala language:

```scala
addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
```

It is recommended to enable warnings for non-unit statements (see [FAQ](./faq.md#what-do-non-unit-statement-warnings-mean)):

```scala
scalacOptions += "-Wnonunit-statement"
```

Alternatively, you can use the Cats Effect 3 Giter8 template, which sets up some basic project infrastructure:

```bash
$ sbt new typelevel/ce3.g8
```

## Your First IO Program

To create a new Cats Effect application, place the following contents into a new Scala file within your project:

```scala mdoc
import cats.effect.{IO, IOApp}

object HelloWorld extends IOApp.Simple {
  val run = IO.println("Hello, World!")
}
```

Once you have saved this file, you should be able to run your application using `sbt run`, and as expected, it will print `Hello, World!` to standard out. Applications written in this style have full access to timers, multithreading, and all of the bells and whistles that you would expect from a full application.

## Understanding IO

Before we go further, it's important to understand what `IO` is. `IO` represents a **description** of a computation that may perform side effects. It's not the computation itself—it's a blueprint that describes what should happen when the computation is eventually executed.

Think of `IO` like a recipe: the recipe itself doesn't cook the meal, but it tells you exactly what steps to follow when you're ready to cook.

### Why IO?

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

For a deeper understanding of `IO`, see the [IO Documentation](io.md).

## IOApp

`IOApp` is the recommended way to structure Cats Effect applications. It provides:

- Automatic runtime management
- Proper shutdown handling
- Signal handling (SIGINT, SIGTERM)
- Resource cleanup

### IOApp.Simple

For simple applications that don't need complex setup:

```scala mdoc
import cats.effect.{IO, IOApp}
import scala.concurrent.duration._

object SimpleApp extends IOApp.Simple {
  val run = 
    IO.println("Starting application") *>
    IO.sleep(1.second) *>
    IO.println("Application running") *>
    IO.sleep(2.seconds) *>
    IO.println("Shutting down")
}
```

### IOApp with Resource Management

For applications that need resource management:

```scala mdoc:silent
import cats.effect.{IO, IOApp, Resource, ExitCode}
import scala.concurrent.duration._

object ResourceApp extends IOApp {
  def run(args: List[String]): IO[ExitCode] = 
    createResource.use { _ =>
      for {
        _ <- IO.println("Using resource")
        _ <- IO.sleep(1.second)
        _ <- IO.println("Resource used successfully")
      } yield ExitCode.Success
    }
    
  def createResource: Resource[IO, String] = 
    Resource.make(
      IO.println("Acquiring resource") *> IO.pure("my-resource")
    )(_ => IO.println("Releasing resource"))
}
```

## Basic IO Operations

### Creating IO Values

```scala mdoc:silent
// From a pure value
val pureValue: IO[String] = IO.pure("Hello")

// From a side effect
val sideEffect: IO[Unit] = IO.println("Hello, World!")

// From a potentially failing operation
val riskyOperation: IO[String] = IO.delay {
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

val safeProgram: IO[String] = riskyProgram.handleErrorWith { error =>
  IO.pure(s"Recovered from: ${error.getMessage}")
}

// Or using attempt to convert to Either
val attemptedProgram: IO[Either[Throwable, String]] = riskyProgram.attempt
```

## Concurrency

Cats Effect makes concurrent programming safe and easy with **Fibers** - lightweight threads that can be created, cancelled, and composed safely.

### Basic Concurrency

```scala mdoc:silent
import cats.syntax.all._

val concurrentProgram: IO[List[String]] = 
  List(
    IO.sleep(100.millis).as("Task 1"),
    IO.sleep(200.millis).as("Task 2"),
    IO.sleep(150.millis).as("Task 3")
  ).parSequence
```

### Starting Fibers

```scala mdoc:silent
val fiberProgram: IO[String] = for {
  fiber <- IO.println("Running in background").start
  _ <- IO.sleep(100.millis)
  _ <- fiber.join
} yield "Done"
```

### Cancellation

```scala mdoc:silent
val cancellableProgram: IO[Unit] = for {
  fiber <- IO.sleep(5.seconds).start
  _ <- IO.sleep(1.second)
  _ <- fiber.cancel
} yield ()
```

## More Complex Example

Here's a more realistic example that demonstrates several Cats Effect concepts:

```scala mdoc
import cats.effect.{IO, IOApp, Ref}
import scala.concurrent.duration._

object FizzBuzzExample extends IOApp.Simple {
  val run = for {
    counter <- Ref[IO].of(0)
    
    // Counter fiber - increments every second
    counterFiber <- (IO.sleep(1.second) *> counter.update(_ + 1)).foreverM.start
    
    // Printer fibers - check counter and print accordingly
    printerFiber <- (for {
      value <- counter.get
      _ <- if (value % 15 == 0) IO.println("FizzBuzz")
          else if (value % 3 == 0) IO.println("Fizz")
          else if (value % 5 == 0) IO.println("Buzz")
          else IO.println(value.toString)
    } yield ()).foreverM.start
    
    // Run for 10 seconds then stop
    _ <- IO.sleep(10.seconds)
    _ <- counterFiber.cancel
    _ <- printerFiber.cancel
  } yield ()
}
```

This example demonstrates:
- **Ref** for shared mutable state
- **Fiber** creation and management
- **Cancellation** of long-running tasks
- **Composition** of concurrent programs

## REPL

Of course, the easiest way to play with Cats Effect is to try it out in a Scala REPL. We recommend using [Ammonite](https://ammonite.io/#Ammonite-REPL) for this kind of thing. To get started, run the following lines (if not using Ammonite, skip the first line and make sure that Cats Effect and its dependencies are correctly configured on the classpath):

```scala
import $ivy.`org.typelevel::cats-effect:3.6.3`

import cats.effect.unsafe.implicits._
import cats.effect.IO

val program = IO.println("Hello, World!")
program.unsafeRunSync()
```

Congratulations, you've just run your first `IO` within the REPL! The `unsafeRunSync()` function is not meant to be used within a normal application. As the name suggests, its implementation is unsafe in several ways, but it is very useful for REPL-based experimentation and sometimes useful for testing.

### Cancelling a long-running REPL task

`unsafeRunCancelable()` is another variant to launch a task, that allows a long-running task to be cancelled. This can be useful to clean up long or infinite tasks that have been spawned from the REPL.

```scala
import cats.effect.unsafe.implicits._
import cats.effect.IO

lazy val loop: IO[Unit] = IO.println("loop until cancel..") >> IO.sleep(2.seconds) >> loop
val cancel = loop.unsafeRunCancelable()
```

`unsafeRunCancelable` starts the loop task running, but also emits an invocable handle value which when invoked (ie `cancel()`) cancels the loop. This can be useful when running in SBT with the `console` command, where by default terminating the REPL doesn't terminate the process, and thus the task will remain executing in the background.

## Testing

### Munit

[![munit-cats-effect Scala version support](https://index.scala-lang.org/typelevel/munit-cats-effect/munit-cats-effect-3/latest-by-scala-version.svg)](https://index.scala-lang.org/typelevel/munit-cats-effect/munit-cats-effect)

The easiest way to write unit tests which use Cats Effect is with [MUnit](https://scalameta.org/munit/) and [MUnit Cats Effect](https://github.com/typelevel/munit-cats-effect). To get started, add the following to your **build.sbt**:

```scala
libraryDependencies += "org.typelevel" %% "munit-cats-effect-3" % "1.0.6" % Test
```

With this dependency, you can now write unit tests which directly return `IO` programs without being forced to run them using one of the `unsafe` functions. This is particularly useful if you're either using ScalaJS (where the fact that the `unsafe` functions block the event dispatcher would result in deadlock), or if you simply want your tests to run more efficiently (since MUnit can run them in parallel):

```scala
import cats.effect.{IO, SyncIO}
import munit.CatsEffectSuite

class ExampleSuite extends CatsEffectSuite {
  test("make sure IO computes the right result") {
    IO.pure(1).map(_ + 2) flatMap { result =>
      IO(assertEquals(result, 3))
    }
  }
}
```

### Weaver-test

[![weaver-cats Scala version support](https://index.scala-lang.org/disneystreaming/weaver-test/weaver-cats/latest-by-scala-version.svg)](https://index.scala-lang.org/disneystreaming/weaver-test/weaver-cats)

[Weaver](https://github.com/disneystreaming/weaver-test) is a test-framework built directly on top of Cats Effect. It is designed specifically to handle thousands of tests exercising I/O layers (http, database calls) concurrently. Weaver makes heavy use of the concurrency constructs and abstractions provided by Cats Effect to safely share resources (clients) across tests and suite, and runs all tests in parallel by default.

To get started, add the following to your **build.sbt**:

```scala
libraryDependencies += "com.disneystreaming" %% "weaver-cats" % "0.7.6" % Test
testFrameworks += new TestFramework("weaver.framework.CatsEffect")
```

Similarly to MUnit, this setup allows you to write your tests directly against `IO`.

```scala
import cats.effect.IO
import weaver._

object ExampleSuite extends SimpleIOSuite {
  test("make sure IO computes the right result") {
    IO.pure(1).map(_ + 2) map { result =>
      expect.eql(result, 3)
    }
  }
}
```


### Other Testing Frameworks

[![core Scala version support](https://index.scala-lang.org/typelevel/cats-effect-testing/core/latest-by-scala-version.svg)](https://index.scala-lang.org/typelevel/cats-effect-testing/core)

If neither MUnit nor Weaver are your speed, the [Cats Effect Testing](https://github.com/typelevel/cats-effect-testing) library provides seamless integration with most major test frameworks. Specifically:

- ScalaTest
- Specs2
- µTest
- MiniTest

Simply add a dependency on the module which is appropriate to your test framework of choice. For example, Specs2:

```scala
libraryDependencies += "org.typelevel" %% "cats-effect-testing-specs2" % "1.2.0" % Test
```

Once this is done, you can write specifications in the familiar Specs2 style, except where each example may now return in `IO`:

```scala
import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect

import org.specs2.mutable.Specification

class ExampleSpec extends Specification with CatsEffect {
  "my example" should {
    "make sure IO computes the right result" in {
      IO.pure(1).map(_ + 2) flatMap { result =>
        IO(result mustEqual 3)
      }
    }
  }
}
```

### ScalaCheck

Special support is available for ScalaCheck properties in the form of the [ScalaCheck Effect](https://github.com/typelevel/scalacheck-effect) project. This library makes it possible to write properties using a special `forAllF` syntax which evaluate entirely within `IO` without blocking threads.

## Next Steps

Now that you have a basic understanding of Cats Effect, here are some recommended next steps:

1. **Read the [Concepts](concepts.md)** page to understand the fundamental abstractions
2. **Follow the [Tutorial](tutorial.md)** for hands-on learning with practical examples
3. **Explore [Recipes](recipes.md)** for common patterns and solutions
4. **Check out Best Practices** for application structure and design
5. **Learn about the [Ecosystem](ecosystem.md)** of libraries that work with Cats Effect

## Common Patterns

### Resource Management

Always use `Resource` for managing external resources:

```scala mdoc:silent
import cats.effect.{IO, Resource}
import java.io.{FileInputStream, FileOutputStream}

def copyFile(src: String, dest: String): IO[Unit] = 
  Resource.make(IO(new FileInputStream(src)))(fis => IO(fis.close()))
    .flatMap { fis =>
      Resource.make(IO(new FileOutputStream(dest)))(fos => IO(fos.close()))
        .map { fos =>
          val buffer = new Array[Byte](1024)
          var bytesRead = fis.read(buffer)
          while (bytesRead != -1) {
            fos.write(buffer, 0, bytesRead)
            bytesRead = fis.read(buffer)
          }
        }
    }
    .use(IO.pure)
```

### Retry Logic

Implement retry with exponential backoff:

```scala mdoc:silent
import cats.effect.IO
import scala.concurrent.duration._

def retryWithBackoff[A](
  io: IO[A], 
  maxRetries: Int = 3,
  initialDelay: FiniteDuration = 100.millis
): IO[A] = {
  def attempt(retriesLeft: Int, delay: FiniteDuration): IO[A] = 
    io.handleErrorWith { error =>
      if (retriesLeft > 0) {
        IO.sleep(delay) *> attempt(retriesLeft - 1, delay * 2)
      } else {
        IO.raiseError(error)
      }
    }
    
  attempt(maxRetries, initialDelay)
}
```

### Timeout

Add timeouts to operations:

```scala mdoc:silent
import cats.effect.IO
import scala.concurrent.duration._

def operationWithTimeout[A](io: IO[A], timeout: FiniteDuration): IO[A] = 
  IO.race(io, IO.sleep(timeout))
    .flatMap {
      case Left(result) => IO.pure(result)
      case Right(_) => IO.raiseError(new RuntimeException("Operation timed out"))
    }
```

These patterns will help you build robust, production-ready applications with Cats Effect.
