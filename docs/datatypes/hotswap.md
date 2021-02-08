---
id: hotswap
title: hotswap
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
  
    Resource.eval {
      for {
        index <- Ref[IO].of(0)
        count <- Ref[IO].of(0)
        //Open the initial log file
        f <- hs.swap(file("0.log"))
        log <- Ref[IO].of(f)
      } yield new Logger[IO] {
        def log(msg: String): IO[Unit] =
          if (msg.length() < n - count)
            log.get.flatMap { file => write(file, msg) } 
            _ <- count.update(_ + msg.length())
          else
            for {
              //Reset the log length counter
              _ <- count.set(msg.length())
              //Increment the counter for the log file name
              idx <- index.modify(n => (n+1, n))
              //Close the old log file and open the new one
              f <- hs.swap(file(s"${idx}.log"))
              _ <- log.set(f)
              _ <- write(f, msg)
            } yield ()
      }
    }
  }
```
