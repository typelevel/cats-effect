---
layout: docsplus
title:  "IOApp"
number: 15
source: "core/shared/src/main/scala/cats/effect/IOApp.scala"
scaladoc: "#cats.effect.IOApp"
---

`IOApp` is a safe application type that describes a `main` 
which executes a [cats.effect.IO](./io.md), as an entry point to 
a pure FP program.

<nav role="navigation" id="toc"></nav>

## Status Quo

Currently in order to specify an entry point to a Java application,
which executes a pure FP program (with side effects suspended and
described by `IO`), you have to do something like this:

```scala mdoc:silent
import cats.effect._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

object Main {
  // Needed for `IO.sleep`
  implicit val timer: Timer[IO] = IO.timer(ExecutionContext.global)
  
  def program(args: List[String]): IO[Unit] =
    IO.sleep(1.second) *> IO(println(s"Hello world!. Args $args"))
    
  def main(args: Array[String]): Unit =
    program(args.toList).unsafeRunSync
}
```

That's dirty, error prone and doesn't work on top of JavaScript.

## Pure Programs

You can now use `cats.effect.IOApp` to describe pure programs:

```scala mdoc:reset:silent
import cats.effect._

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
interruption signal that can be caught, then the `IO` app will cancel
and safely release any resources.

*WARNING*: If you run your `IOApp` program from sbt, you may observe
cancellation and resource releasing is *not* happening. This is due to
sbt, by default, running programs in the _same_ JVM as sbt, so when
your program is canceled sbt avoids stopping its own JVM. To properly
allow cancellation, ensure your progam is [forked into its own JVM](https://www.scala-sbt.org/1.x/docs/Forking.html#Enable+forking)
via a setting like `fork := true` in your sbt configuration.

For example:

```scala mdoc:reset:silent
import cats.effect.ExitCase.Canceled
import cats.effect._
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

- if you leave it for 10 seconds, it will print "*normal exit*" and exit normally
- if you press `Ctrl-C` or do a `kill $pid` from the terminal, then it will immediately print
  "*interrupted: releasing and exiting*" and exit
  
Therefore `IOApp` automatically installs an interruption handler for you.

## Why Is It Specialized for IO?

`IOApp` doesn't have an `F[_]` parameter, unlike the other data types 
exposed by Cats-Effect. This is because different `F[_]` data types have 
different requirements for evaluation at the end of the world.

For example `cats.effect.IO` now needs a `ContextShift[IO]` in scope
for working with `Concurrent` and thus for getting the
`ConcurrentEffect` necessary to evaluate an `IO`. It also needs a
`Timer[IO]` in scope for utilities such as `IO.sleep` and `timeout`.
 
[ContextShift](../datatypes/contextshift.md) and
[Timer](../datatypes/timer.md) are provided by the environment and
in this case the environment is the `IOApp`. Monix's
[Task](https://monix.io/docs/3x/eval/task.html) however has global
`ContextShift[Task]` and `Timer[Task]` always in scope and doesn't
need them, but it does need a
[Scheduler](https://monix.io/docs/3x/execution/scheduler.html) to be
available for the necessary [Effect](../typeclasses/effect.md) instance. And both
Cats-Effect's `IO` and Monix's `Task` are cancelable, in which case it
is desirable for the `IOApp` / `TaskApp` to install shutdown handlers
to execute in case of interruption, however our type classes can also
work with non-cancelable data types, in which case handling
interruption is no longer necessary.

Long story short, it's better for `IOApp` to be specialized and
each `F[_]` can come with its own app data type that is better suited
for its needs. For example Monix's `Task` comes with its own `TaskApp`.

That said `IOApp` can be used for any `F[_]`, because any `Effect`
or `ConcurrentEffect` can be converted to `IO`. Example:

```scala mdoc:reset:silent
import cats.effect._
import cats.data.EitherT

object Main extends IOApp {
  type F[A] = EitherT[IO, Throwable, A]
  val F = implicitly[ConcurrentEffect[F]]

  def run(args: List[String]) = 
    F.toIO {
      EitherT.right(IO(println("Hello from EitherT")))
        .map(_ => ExitCode.Success)
    }
}
```

## Final Words

`IOApp` is awesome for describing pure FP programs and gives you functionality 
that does not come for free when using the normal Java main protocol, like the
interruption handler.

And we worked hard to make this behavior available on top of JavaScript, via 
Scala.js, so you can use this for your Node.js apps as well.
