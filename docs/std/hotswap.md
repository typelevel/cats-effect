---
id: hotswap
title: Hotswap
---

## Motivation

Constructing a new [`Resource`](./resource.md) inside the body of a
`Resource#use` can lead to memory leaks as the outer resource is not finalized
until after the inner resource is released. Consider for example writing a
logger that will rotate log files every `n` bytes. 

## Hotswap

`Hotswap` addresses this by exposing a linear sequence of resources as a single
`Resource`. We can run the finalizers for the current resource and advance to
the next one in the sequence using `Hotswap#swap`. An error may be raised if
the previous resource in the sequence is referenced after `swap` is invoked
(as the resource will have been finalized).

```scala
sealed trait Hotswap[F[_], R] {

  def swap(next: Resource[F, R]): F[R]
}
```

A rotating logger would then look something like this:

```scala
def rotating(n: Int): Resource[IO, Logger[IO]] =
  Hotswap.create[IO, File].flatMap { hs =>
    def file(name: String): Resource[IO, File] = ???
    def write(file: File, msg: String): IO[Unit] = ???

    Resource.eval {
      for {
        index <- Ref[IO].of(0)
        count <- Ref[IO].of(0)
        // Open the initial log file
        _ <- hs.swap(file("0.log"))
      } yield new Logger[IO] {
        def log(msg: String): IO[Unit] =
          count.get.flatMap { currentCount =>
            if (msg.length() < n - currentCount)
              hs.get.use { currentFile =>
                write(currentFile, msg) *>
                  count.update(_ + msg.length())
              }
            else
              for {
                // Reset the log length counter
                _ <- count.set(msg.length())
                // Increment the counter for the log file name
                idx <- index.updateAndGet(_ + 1)
                // Close the old log file and open the new one
                _ <- hs.swap(file(s"$idx.log"))
                _ <- hs.get.use(write(_, msg))
              } yield ()
          }
      }
    }
  }
```
