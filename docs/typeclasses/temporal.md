---
id: temporal
title: Temporal
---

`Temporal` extends `Concurrent` with the ability to suspend a fiber by sleeping for
a specified duration.

```scala
firstThing >> IO.sleep(5.seconds) >> secondThing
```

Note that this should *always* be used instead of `IO(Thread.sleep(duration))`. TODO
link to appropriate section on `Sync`, blocking operations and underlying threads

This enables us to define powerful time-dependent
derived combinators like `timeoutTo`:

```scala
val data = fetchFromRemoteService.timeoutTo(2.seconds, cachedValue)
```
