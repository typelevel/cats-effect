---
id: getting-started
title: Getting Started
---

Add the following to your **build.sbt**:

```scala
libraryDependencies += "org.typelevel" %% "cats-effect" % "3.2.8"
```

Naturally, if you're using ScalaJS, you should replace the double `%%` with a triple `%%%`. If you're on Scala 2, it is *highly* recommended that you enable the [better-monadic-for](https://github.com/oleg-py/better-monadic-for) plugin, which fixes a number of surprising elements of the `for`-comprehension syntax in the Scala language:

```scala
addCompilerPlugin("com.olegpy" %% "better-monadic-for" % "0.3.1")
```

Alternatively, you can use the Cats Effect 3 Giter8 template, which sets up some basic project infrastructure:

```bash
$ sbt new typelevel/ce3.g8
```

To create a new Cats Effect application, place the following contents into a new Scala file within your project:

```scala mdoc
import cats.effect.{IO, IOApp}

object HelloWorld extends IOApp.Simple {
  val run = IO.println("Hello, World!")
}
```

Once you have saved this file, you should be able to run your application using `sbt run`, and as expected, it will print `Hello, World!` to standard out. Applications written in this style have full access to timers, multithreading, and all of the bells and whistles that you would expect from a full application. For example, here's a very silly version of FizzBuzz which runs four concurrent lightweight threads, or *fibers*, one of which counts up an `Int` value once per second, while the others poll that value for changes and print in response:

```scala mdoc
import cats.effect.{IO, IOApp}
import scala.concurrent.duration._

// obviously this isn't actually the problem definition, but it's kinda fun
object StupidFizzBuzz extends IOApp.Simple {
  val run = 
    for {
      ctr <- IO.ref(0)

      wait = IO.sleep(1.second)
      poll = wait *> ctr.get

      _ <- poll.flatMap(IO.println(_)).foreverM.start
      _ <- poll.map(_ % 3 == 0).ifM(IO.println("fizz"), IO.unit).foreverM.start
      _ <- poll.map(_ % 5 == 0).ifM(IO.println("buzz"), IO.unit).foreverM.start

      _ <- (wait *> ctr.update(_ + 1)).foreverM.void
    } yield ()
}
```

We will learn more about constructs like `start` and `*>` in later pages, but for now notice how easy it is to compose together concurrent programs based on simple building blocks. Additionally, note the reuse of the `wait` and `poll` programs. Because we're describing our program *as a value* (an `IO`), we can reuse that value as part of many different programs, and it will continue to behave the same regardless of this duplication.

## REPL

Of course, the easiest way to play with Cats Effect is to try it out in a Scala REPL. We recommend using [Ammonite](https://ammonite.io/#Ammonite-REPL) for this kind of thing. To get started, run the following lines (if not using Ammonite, skip the first line and make sure that Cats Effect and its dependencies are correctly configured on the classpath):

```scala
import $ivy.`org.typelevel::cats-effect:3.2.8`

import cats.effect.unsafe.implicits._ 
import cats.effect.IO 

val program = IO.println("Hello, World!") 
program.unsafeRunSync() 
```

Congratulations, you've just run your first `IO` within the REPL! The `unsafeRunSync()` function is not meant to be used within a normal application. As the name suggests, its implementation is unsafe in several ways, but it is very useful for REPL-based experimentation and sometimes useful for testing.

## Testing

The easiest way to write unit tests which use Cats Effect is with [MUnit](https://scalameta.org/munit/) and [MUnit Cats Effect](https://github.com/typelevel/munit-cats-effect). To get started, add the following to your **build.sbt**:

```scala
libraryDependencies += "org.typelevel" %% "munit-cats-effect-3" % "1.0.3" % Test
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

### Other Testing Frameworks

If MUnit isn't your speed, the [Cats Effect Testing](https://github.com/typelevel/cats-effect-testing) library provides seamless integration with most major test frameworks. Specifically:

- ScalaTest
- Specs2
- µTest
- MiniTest

Simply add a dependency on the module which is appropriate to your test framework of choice. For example, Specs2:

```scala
libraryDependencies += "org.typelevel" %% "cats-effect-testing-specs2" % "1.1.1" % Test
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
