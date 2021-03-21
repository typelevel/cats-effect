---
id: temporal
title: Temporal
---

`Temporal` extends `Concurrent` with the ability to suspend a fiber by sleeping for
a specified duration.

```scala
firstThing >> Temporal[F].sleep(5.seconds) >> secondThing
```

Of course this could be achieved by `Sync[F].delay(Thread.sleep(duration))` but
this is a _very bad_ idea as it will block a thread from the compute pool (see
the [thread model docs](../thread-model.md) for more details on why this is
bad).  Instead, `Temporal[F]#sleep` is assigned its own typeclass and is a
primitive of the implementation that semantically blocks the execution of the
calling  fiber by de-scheduling it.  Internally a scheduler is used to wait for
the specified duration before rescheduling the fiber.

The ability to sleep for a specified duration enables us to define powerful
time-dependent derived combinators like `timeoutTo`:

```scala
val data = fetchFromRemoteService.timeoutTo(2.seconds, cachedValue)
```
