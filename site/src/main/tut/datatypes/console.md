---
layout: docsplus
title:  "Console"
number: 16
source: "core/shared/src/main/scala/cats/effect/Console.scala"
scaladoc: "#cats.effect.Console"
---

`Console` is a convenient addon that provides common methods to read/write from/to the standard input/output stream.

```tut:silent
trait Console[F[_]] {
trait Console[F[_]] {
  def putStrLn(str: String): F[Unit]
  def error(str: String): F[Unit]
  def readLine: F[String]
  // and some more...
}
```

## Integration with `IO`

All you need to have is the `import cats.effect.Console.io._` in scope. Eg:

```tut:silent
import cats.effect.IO
import cats.effect.Console.io._

val program: IO[Unit] =
  for {
    _ <- putStrLn("Enter your name: ")
    n <- readLine
    _ <- putStrLn(s"Welcome $n!")
  } yield ()
```

## Integration with a polymorphic `F[_]`

You can either implement your own instance of `Console[F[_]]` by extending it or use the default instance defined for `Sync[F]`. Here's an example with the latter as an implicit instance:

```tut:reset:silent
import cats.effect.{Console, Sync, SyncConsole}
import cats.syntax.all._

implicit def console[F[_]: Sync]: Console[F] = new SyncConsole[F]

def program[F[_]: Sync](implicit C: Console[F]): F[Unit] =
  for {
    _ <- C.putStrLn("Enter your name: ")
    n <- C.readLine
    _ <- C.putStrLn(s"Welcome $n!")
  } yield ()
```

**NOTE:** All the writing methods (`putStrln`, `error`, etc) accept any parameter of type `A` that define a `Show[A]` instance.
