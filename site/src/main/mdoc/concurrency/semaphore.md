---
layout: docsplus
title:  "Semaphore"
number: 15
source: "shared/src/main/scala/cats/effect/concurrent/Semaphore.scala"
scaladoc: "#cats.effect.concurrent.Semaphore"
---

{:.responsive-pic}
![concurrency semaphore](../img/concurrency-semaphore.png)

A semaphore has a non-negative number of permits available. Acquiring a permit decrements the current number of permits and releasing a permit increases the current number of permits. An acquire that occurs when there are no permits available results in semantic blocking until a permit becomes available.

```scala
abstract class Semaphore[F[_]] {
  def available: F[Long]
  def acquire: F[Unit]
  def release: F[Unit]
  // ... and more
}
```

## Semantic Blocking and Cancellation

Semaphore does what we call "semantic" blocking, meaning that no actual threads are 
being blocked while waiting to acquire a permit.

Behavior on cancellation:

- Blocking acquires are cancelable if the semaphore is created with `Semaphore.apply` (and hence, with a `Concurrent[F]` instance).
- Blocking acquires are non-cancelable if the semaphore is created with `Semaphore.uncancelable` (and hence, with an `Async[F]` instance).

## Shared Resource

When multiple processes try to access a precious resource you might want to constraint the number of accesses. Here is where `Semaphore[F]` is useful.

Three processes are trying to access a shared resource at the same time but only one at a time will be granted access and the next process have to wait until the resource gets available again (availability is one as indicated by the semaphore counter).

`R1`, `R2` & `R3` will request access of the precious resource concurrently so this could be one possible outcome:

```
R1 >> Availability: 1
R2 >> Availability: 1
R2 >> Started | Availability: 0
R3 >> Availability: 0
--------------------------------
R1 >> Started | Availability: 0
R2 >> Done | Availability: 0
--------------------------------
R3 >> Started | Availability: 0
R1 >> Done | Availability: 0
--------------------------------
R3 >> Done | Availability: 1
```

This means when `R1` and `R2` requested the availability it was one and `R2` was faster in getting access to the resource so it started processing. `R3` was the slowest and saw that there was no availability from the beginning.

Once `R2` was done `R1` started processing immediately showing no availability. Once `R1` was done `R3` started processing immediately showing no availability. Finally, `R3` was done showing an availability of one once again.

```scala mdoc:reset:silent
import cats.effect.{Concurrent, IO, Timer}
import cats.effect.concurrent.Semaphore
import cats.implicits._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

// Needed for getting a Concurrent[IO] instance
implicit val ctx = IO.contextShift(ExecutionContext.global)
// Needed for `sleep`
implicit val timer = IO.timer(ExecutionContext.global)

class PreciousResource[F[_]](name: String, s: Semaphore[F])(implicit F: Concurrent[F], timer: Timer[F]) {
  def use: F[Unit] =
    for {
      x <- s.available
      _ <- F.delay(println(s"$name >> Availability: $x"))
      _ <- s.acquire
      y <- s.available
      _ <- F.delay(println(s"$name >> Started | Availability: $y"))
      _ <- timer.sleep(3.seconds)
      _ <- s.release
      z <- s.available
      _ <- F.delay(println(s"$name >> Done | Availability: $z"))
    } yield ()
}

val program: IO[Unit] =
  for {
    s  <- Semaphore[IO](1)
    r1 = new PreciousResource[IO]("R1", s)
    r2 = new PreciousResource[IO]("R2", s)
    r3 = new PreciousResource[IO]("R3", s)
    _  <- List(r1.use, r2.use, r3.use).parSequence.void
  } yield ()
```
