---
id: clock
title: Clock
---

A typeclass that provides effectful monotonic and system time analogous to
`System.nanoTime()` and `System.currentTimeMillis()`

```scala
trait Clock[F[_]] {

  def monotonic: F[FiniteDuration]

  def realTime: F[FiniteDuration]

}
```
