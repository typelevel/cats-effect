---
id: clock
title: Clock
---

A typeclass that provides effectful monotonic and system time analogous to
`System.nanoTime()` and `System.currentTimeMillis()`

```scala mdoc:silent
import scala.concurrent.duration._
trait Clock[F[_]] {

  def monotonic: F[FiniteDuration]

  def realTime: F[FiniteDuration]

}
```
