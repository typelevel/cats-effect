---
id: semaphore
title:  Semaphore
---

![](assets/semaphore.png)

A semaphore has a non-negative number of permits available. Acquiring a permit decrements the current number of permits and releasing a permit increases the current number of permits. An acquire that occurs when there are no permits available results in fiber blocking until a permit becomes available.

```scala
abstract class Semaphore[F[_]] {
  def available: F[Long]
  def acquire: F[Unit]
  def release: F[Unit]
  // ... and more
}
```

## Fiber Blocking and Cancellation

Semaphore does what we call "fiber" blocking (aka "semantic" blocking), meaning that no actual threads are
being blocked while waiting to acquire a permit. Blocking acquires are cancelable.

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
import cats.effect.{IO, Temporal}
import cats.effect.std.{Console, Semaphore}
import cats.implicits._
import cats.effect.syntax.all._

import scala.concurrent.duration._

class PreciousResource[F[_]: Temporal](name: String, s: Semaphore[F])(implicit F: Console[F]) {
  def use: F[Unit] =
    for {
      x <- s.available
      _ <- F.println(s"$name >> Availability: $x")
      _ <- s.acquire
      y <- s.available
      _ <- F.println(s"$name >> Started | Availability: $y")
      _ <- s.release.delayBy(3.seconds)
      z <- s.available
      _ <- F.println(s"$name >> Done | Availability: $z")
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
