---
id: fiber-dumps
title: Fiber Dumps
---

## Introduction

In the previous section, we learned how `IO` uses [Tracing](tracing.md) to generate enhanced exceptions that enable us to introspect the execution graph of a fiber, even across asynchronous boundaries.
As powerful as this is, one shortcoming is that this trace can only be observed when raising an exception or via an explicit `IO.trace` operation, although there are many other situations in which such introspection is extremely valuable, for example to diagnose a deadlocked program.

Fiber dumps solve _exactly_ this problem, by providing a mechanism to view the traces for (nearly) _all_ the fibers in your program at _any_ given time. Essentially, it is `IO`'s answer to thread dumps. This is a sophisticated feature that has been carefully implemented to minimize overhead without memory leaks of terminated fibers.

## Getting a fiber dump

For a running `IOApp` with tracing enabled, it's as simple as <kbd>Ctrl</kbd>-<kbd>T</kbd>. This is supported for _both_ the JVM and Node.js runtimes on macOS/BSD and Linux.

More specifically, sending the `SIGINFO` (macOS/BSD) or `SIGUSR1` (Linux) signals to your app will trigger a fiber dump to `STDERR`.

_TODO, how to do this programatically for Windows/browsers, also mbeans stuff?_

## Interpreting a fiber dump

Each fiber in the dump is indicated to be in one of three states:
1. `RUNNING`: this fiber is running, on a thread, right now.
2. `YIELDING`: this fiber is queued on the thread pool, but is currently _yielded_ while other fibers are running.
3. `WAITING`: this fiber is semantically blocked, waiting for an async callback, or a `Deferred`, etc.

Interpretation of traces is decribed on the [Tracing](tracing.md) page.

```
cats.effect.IOFiber@494388963 RUNNING
 ├ flatMap @ TestApp$.$anonfun$loop$2(TestApp.scala:27)
 ├ >> @ TestApp$.loop$lzycompute(TestApp.scala:27)
 ├ map @ TestApp$.loop$lzycompute(TestApp.scala:26)
 ├ flatMap @ TestApp$.$anonfun$loop$2(TestApp.scala:27)
 ├ flatMap @ TestApp$.$anonfun$loop$2(TestApp.scala:27)
 ├ >> @ TestApp$.loop$lzycompute(TestApp.scala:27)
 ├ >> @ TestApp$.loop$lzycompute(TestApp.scala:27)
 ├ map @ TestApp$.loop$lzycompute(TestApp.scala:26)
 ├ flatMap @ TestApp$.$anonfun$loop$2(TestApp.scala:27)
 ├ >> @ TestApp$.loop$lzycompute(TestApp.scala:27)
 ├ >> @ TestApp$.loop$lzycompute(TestApp.scala:27)
 ├ map @ TestApp$.loop$lzycompute(TestApp.scala:26)
 ├ flatMap @ TestApp$.$anonfun$loop$2(TestApp.scala:27)
 ├ >> @ TestApp$.loop$lzycompute(TestApp.scala:27)
 ├ >> @ TestApp$.loop$lzycompute(TestApp.scala:27)
 ╰ flatMap @ TestApp$.$anonfun$loop$2(TestApp.scala:27)

cats.effect.IOFiber@1433009074 YIELDING

cats.effect.IOFiber@1464108890 WAITING
 ├ flatMap @ TestApp$.$anonfun$run$2(TestApp.scala:34)
 ╰ flatMap @ TestApp$.$anonfun$run$1(TestApp.scala:33)
 
... many many more fibers
```
