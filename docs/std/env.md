---
id: env
title: Env
---

`Env` provides a handy way of reading the process's environment variables.

```scala
trait Env[F[_]] {
  def get(name: String): F[Option[String]]
  def entries: F[Iterable[(String, String)]]
}
```

```scala mdoc:silent
import cats.effect.IO
import cats.effect.std.Env

val program: IO[Unit] =
  for {
    variable <- Env[IO].get("MY_VARIABLE")
    _ <- IO.println(s"The variable is [${variable}]")    
  } yield ()  
```

### Why `def get(name): F[Option[String]]` rather than `def get(name): Option[String]`?

Technically, environment variables can be set within the **current** process on a POSIX system.  
Java doesn't provide that functionality in a public API, but it's possible via JNA API or in Scala Native world.

Besides the modification, retrieving an environment variable may fail under some circumstances.  
For example, the process may throw a `SecurityException` on Windows when the permissions are insufficient.

Since the library supports multiple platforms (JVM, Scala Native, ScalaJS), the implementation should take
platform-specific peculiarities into the account.
