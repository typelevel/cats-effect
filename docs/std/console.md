---
id: console
title: Console
---

`Console` provides common methods to write to and read from the standard console. 
Suited only for extremely simple console input and output, therefore do not use it as a logging tool.

```scala
trait Console[F[_]] {
  def print[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit]
  def println[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit]
  def error[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit]
  def errorln[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): F[Unit]
  def printStackTrace(t: Throwable): F[Unit]
}
```

## Using `Console`

```scala mdoc:silent
import cats.effect.IO
import cats.effect.std.Console

val program: IO[Unit] =
  for {
    _ <- Console[IO].println("Please enter your name: ")
    n <- Console[IO].readLine
    _ <- if (n.nonEmpty) Console[IO].println("Hello, " + n) else Console[IO].errorln("Name is empty!")
  } yield ()
```

## Platform-specific limitations of `readLine`

### JVM

The `readLine` is not always cancelable on JVM, for reasons outside our control. 
For example, the following code will deadlock:
```scala
IO.readLine.timeoutTo(3.seconds, IO.pure("hello"))
```

The problem arises from the underlying implementation of `read` in the `System.in` input stream. 
The only way to complete the `read` is to supply some actual data or close the input.

### Scala Native

Similarly to JVM, `readLine` is not always cancelable.

### Scala.JS

`readLine` is not implemented for Scala.js. On Node.js consider using `fs2.io.stdin`.
