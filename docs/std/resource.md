---
id: resource
title: Resource
---

## Motivation

A common pattern is to acquire a resource (eg a file or a socket), perform
some action on it and then run a finalizer (eg closing the file handle),
regardless of the outcome of the action.

This can be achieved with `MonadCancel#bracket`

```scala
def bracket[A, B](acquire: F[A])(use: A => F[B])(release: A => F[Unit]): F[B]
```

However, composing this quickly becomes unwieldy

```scala
val concat: IO[Unit] = IO.bracket(openFile("file1")) { file1 =>
  IO.bracket(openFile("file2")) { file2 =>
    IO.bracket(openFile("file3")) { file3 =>
      for {
        bytes1 <- read(file1)
        bytes2 <- read(file2)
        _ <- write(file3, bytes1 ++ bytes2)
      } yield ()
    }(file3 => close(file3))
  }(file2 => close(file2))
}(file1 => close(file1))
```

and it also couples the logic to acquire the resource with the logic to use
the resource.

`Resource[F[_], A]` is a solution to this which encapsulates the logic
to acquire and finalize a resource of type `A` and forms a `Monad`
in `A` so that we can construct composite resources without the
deep nesting of `bracket`.

## Resource

The simplest way to construct a `Resource` is with `Resource#make` and the simplest way to
consume a resource is with `Resource#use`. Arbitrary actions can also be lifted to
resources with `Resource#eval`

```scala
object Resource {
  def make[F[_], A](acquire: F[A])(release: A => F[Unit]): Resource[F, A]

  def eval[F[_], A](fa: F[A]): Resource[F, A]
}

abstract class Resource[F, A] {
  def use[B](f: A => F[B]): F[B]
}
```

Our concat example then becomes:

```scala
def file(name: String): Resource[IO, File] = Resource.make(openFile(name))(file => close(file))

val concat: IO[Unit] =
  (
    for {
      in1 <- file("file1")
      in2 <- file("file2")
      out <- file("file3")
    } yield (in1, in2, out)
  ).use { case (file1, file2, file3) =>
    for {
      bytes1 <- read(file1)
      bytes2 <- read(file2)
      _ <- write(file3, bytes1 ++ bytes2)
    } yield ()
  
  }
```

Note that the resources are released in reverse order to the acquire and that
both `acquire` and `release` are non-interruptible and hence safe in the face of
cancelation. Outer resources will be released irrespective of failure in the
lifecycle of an inner resource.

Also note that finalization happens as soon as the `use` block finishes and
hence the following will throw an error as the file is already closed when we
attempt to read it:
```scala
open(file1).use(IO.pure).flatMap(readFile)
```
As a corollary, this means that the resource is acquired every time `use` is invoked.
So `file.use(read) >> file.use(read)` opens the file twice whereas
`file.use { file => read(file) >> read(file) }` will only open it once.
