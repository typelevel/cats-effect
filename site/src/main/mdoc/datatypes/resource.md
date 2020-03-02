---
layout: docsplus
title:  "Resource"
number: 14
source: "core/shared/src/main/scala/cats/effect/Resource.scala"
scaladoc: "#cats.effect.Resource"
---

Effectfully allocates and releases a resource. Forms a `MonadError` on the resource type when the effect type has a `Bracket` instance.

The [Acquiring and releasing `Resource`s](../tutorial/tutorial.html#acquiring-and-releasing-resources) section of the tutorial provides some additional context and examples regarding `Resource`.

```scala mdoc:silent
import cats.effect.Bracket

abstract class Resource[F[_], A] {
  def use[B](f: A => F[B])(implicit F: Bracket[F, Throwable]): F[B]
}
```

Nested resources are released in reverse order of acquisition. Outer resources are released even if an inner acquisition, use or release fails.

You can lift any `F[A]` with an `Applicative` instance into a `Resource[F, A]` with a no-op release via `Resource.liftF`:

```scala mdoc:reset
import cats.effect.{IO, Resource}

val greet: String => IO[Unit] = x => IO(println("Hello " ++ x))

Resource.liftF(IO.pure("World")).use(greet).unsafeRunSync
```

Moreover it's possible to apply further effects to the wrapped resource without leaving the `Resource` context via `evalMap`:

```scala mdoc:reset
import cats.effect.{IO, Resource}

val acquire: IO[String] = IO(println("Acquire cats...")) *> IO("cats")
val release: String => IO[Unit] = _ => IO(println("...release everything"))
val addDogs: String => IO[String] = x =>
  IO(println("...more animals...")) *> IO.pure(x ++ " and dogs")
val report: String => IO[String] = x =>
  IO(println("...produce weather report...")) *> IO("It's raining " ++ x)

Resource.make(acquire)(release).evalMap(addDogs).use(report).unsafeRunSync
```

### Example

```scala mdoc:reset:silent
import cats.effect.{IO, Resource}

def mkResource(s: String) = {
  val acquire = IO(println(s"Acquiring $s")) *> IO.pure(s)

  def release(s: String) = IO(println(s"Releasing $s"))

  Resource.make(acquire)(release)
}

val r = for {
  outer <- mkResource("outer")
  inner <- mkResource("inner")
} yield (outer, inner)

r.use { case (a, b) => IO(println(s"Using $a and $b")) }.unsafeRunSync
```

If using an AutoCloseable create a resource without the need to specify how to close.

### Examples

#### With `scala.io.Source`

```scala mdoc:reset:silent
import cats.effect._

val acquire = IO {
  scala.io.Source.fromString("Hello world")
}

Resource.fromAutoCloseable(acquire).use(source => IO(println(source.mkString))).unsafeRunSync()
```

#### With `java.io` using IO

```scala mdoc:reset:silent
import java.io._
import collection.JavaConverters._
import cats.effect._

def readAllLines(bufferedReader: BufferedReader, blocker: Blocker)(implicit cs: ContextShift[IO]): IO[List[String]] =
  blocker.delay[IO, List[String]] {
    bufferedReader.lines().iterator().asScala.toList
  }

def reader(file: File, blocker: Blocker)(implicit cs: ContextShift[IO]): Resource[IO, BufferedReader] =
  Resource.fromAutoCloseableBlocking(blocker)(IO {
      new BufferedReader(new FileReader(file))
    }
  )

def readLinesFromFile(file: File, blocker: Blocker)(implicit cs: ContextShift[IO]): IO[List[String]] = {
  reader(file, blocker).use(br => readAllLines(br, blocker))
}
```

#### A `java.io` example agnostic of the effect type

```scala mdoc:reset:silent
import java.io._
import cats.effect._
import cats.syntax.flatMap._

def reader[F[_]](file: File, blocker: Blocker)(implicit F: Sync[F], cs: ContextShift[F]): Resource[F, BufferedReader] =
  Resource.fromAutoCloseableBlocking(blocker)(F.delay {
    new BufferedReader(new FileReader(file))
  })

def dumpResource[F[_]](res: Resource[F, BufferedReader], blocker: Blocker)(implicit F: Sync[F], cs: ContextShift[F]): F[Unit] = {
  def loop(in: BufferedReader): F[Unit] =
    F.suspend {
      blocker.delay(in.readLine()).flatMap { line =>
        if (line != null) {
          System.out.println(line)
          loop(in)
        } else {
          F.unit
        }
      }
    }
  res.use(loop)
}

def dumpFile[F[_]](file: File, blocker: Blocker)(implicit F: Sync[F], cs: ContextShift[F]): F[Unit] =
  dumpResource(reader(file, blocker), blocker)
```
