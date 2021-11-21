---
id: sync
title: Sync
---

`Sync` is the synchronous FFI for suspending side-effectful operations. The
means of suspension is dependent on whether the side effect you want to
suspend is blocking or not (see the [thread model docs](../thread-model.md)
for more details on why this is the case).

## Methods of suspension

If your side effect is not thread-blocking then you can use `Sync[F].delay`
```scala
val counter = new AtomicLong()

val inc: F[Long] = Sync[F].delay(counter.incrementAndGet())
```

If your side effect is thread blocking then you should use `Sync[F].blocking`,
which not only suspends the side effect but also shifts the evaluation
of the effect to a separate threadpool to avoid blocking the compute
threadpool. Execution is shifted back to the compute pool once
the blocking operation completes.
```scala
val contents: F[String] = Sync[F].blocking(Source.fromFile("file").mkString)
```

A downside of thread-blocking calls is that the fiber executing them is not
cancelable until the blocking call completes. If you have a very long-running
blocking operation then you may want to suspend it using `Sync[F].interruptible`
instead.  This behaves the same as `blocking` but will attempt to interrupt the
blocking operation via a thread interrupt in the event on cancelation.

```scala
//true means we try thread interruption repeatedly until the blocking operation exits
val operation: F[Unit] = F.interruptibleMany(longRunningOp())

val run: F[Unit] = operation.timeout(30.seconds)
```
