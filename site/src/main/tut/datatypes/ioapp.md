---
layout: docsplus
title:  "Resource"
number: 15
source: "core/shared/src/main/scala/cats/effect/IOApp.scala"
scaladoc: "#cats.effect.IOApp"
---

`IOApp` is a safe application type that describes a `main` 
which executes a [cats.effect.IO](./io.html), as an entry point to 
a pure FP program.

## Status Quo

Currently in order to specify an entry point to a Java application,
which executes a pure FP program (with side effects suspended and
described by `IO`), you have to do something like this:

```tut:silent
import cats.effect._
import cats.syntax.all._
import scala.concurrent.duration._

// Currently needed for Timer[IO] on top of the JVM, which is a
// requirement for concurrent operations or for sleeps
import scala.concurrent.ExecutionContext.Implicits.global

object Main {
  def program(args: List[String]): IO[Unit] =
    IO.sleep(1.second) *> IO(println("Hello world!"))
    
  def main(args: Array[String]): Unit =
    program(args.toList).unsafeRunSync
}
```

That's dirty, error prone and doesn't work on top of JavaScript.

## Pure Programs

You can now use `cats.effect.IOApp` to describe pure programs:

```tut:reset:silent
import cats.effect._
import cats.syntax.all._

object Main extends IOApp {

  def run(args: List[String]): IO[ExitCode] =
    args.headOption match {
      case Some(name) =>
        IO(println(s"Hello, $name.")).as(ExitCode.Success)
      case None =>
        IO(System.err.println("Usage: MyApp name")).as(ExitCode(2))
    }  
}
```

Things to note:

1. the command line arguments get passed as a pure `List` instead of an `Array`
2. we use an `ExitCode` to specify success or an error code, the implementation 
   handling how that is returned and thus you no longer have to deal with a
   side-effectful `Runtime.exit` call
3. the `Timer[IO]` dependency is already provided by `IOApp`,
   so on top of the JVM there's no longer a need for an implicit
   `ExecutionContext` to be in scope
   
In terms of the behavior, the contract is currently this:

- if the program returns an `ExitCode.Success`, the main method exits and
  shutdown is handled by the platform — this is meant to play nice with 
  Spark and also be consistent with Java's behavior (e.g. non-daemon threads
  will block the app from exiting, unless you do something about it)
- if completed with an exit code different from zero, the app is exited
  with that as an error code (via `sys.exit`)
- if the `IO` terminates in error, it is printed to standard error and
  `sys.exit` is called
  
### Cancelation and Safe Resource Release

The `cats.effect.IO` implementation is cancelable and so is `IOApp`.

This means that when `IOApp` receives a `SIGABORT`, `SIGINT` or another
interruption signal that can be caught, then the `IO` app will cancel,
safely release any resources.

For example:

```tut:silent
import cats.effect.ExitCase.Canceled
import cats.effect._
import cats.syntax.all._
import scala.concurrent.duration._

object Main extends IOApp {

  def loop(n: Int): IO[ExitCode] =
    IO.suspend {
      if (n < 10)
        IO.sleep(1.second) *> IO(println(s"Tick: $n")) *> loop(n + 1)
      else
        IO.pure(ExitCode.Success)
    }

  def run(args: List[String]): IO[ExitCode] =
    loop(0).guaranteeCase {
      case Canceled =>
        IO(println("Interrupted: releasing and exiting!"))
      case _ =>
        IO(println("Normal exit!"))
    }
}
```

If you run this sample, you can get two outcomes:

- if you leave it for 10 seconds, it wil print "*normal exit*" and exit normally
- if you press `Ctrl-C` or do a `kill $pid` from the terminal, then it will immediately print
  "*interrupted: releasing and exiting*" and exit
  
Therefore `IOApp` automatically installs an interruption handler for you.

## Final Words

`IOApp` is awesome for describing pure FP programs and gives you functionality 
that does not come for free when using the normal Java main protocol, like the
interruption handler.

And we worked hard to make this behavior available on top of JavaScript, via 
Scala.js, so you can use this for your Node.js apps as well.
