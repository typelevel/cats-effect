---
id: fiber-dumps
title: Fiber Dumps
---

One of the most annoying and difficult problems to resolve in any asynchronous application is the *asynchronous deadlock*. This scenario happens when you have a classic deadlock of some variety, where one fiber is waiting for a second fiber which is in turn waiting for the first (in the simplest case). Due to the asynchronous nature of the runtime, whenever a fiber blocks, all references to it are removed from the internal runtime, meaning that a deadlock generally leaves absolutely no residue whatsoever, and the only recourse as a developer is to just start sprinkling `IO.println` expressions around the code to see if you can figure out where it's getting stuck.

This is very much in contrast to a conventional deadlock in a synchronous runtime, where we have JVM-level tools such as thread dumps to suss out where things are stuck. In particular, *thread dumps* are a commonly-applied low level tool offered by the JVM which can serve to inform users of what threads are active at that point in time and what each of their call stacks are. This tool is generally quite useful, but it becomes even more useful when the threads are deadlocked: the call stacks show exactly where each thread is blocked, making it relatively simple to reconstruct what they are blocked on and thus how to untie the knot.

*Fiber* dumps are a similar construct for Cats Effect applications. Even better, you don't need to change *anything* in order to take advantage of this functionality. As a simple example, here is an application which trivially deadlocks:

```scala
import cats.effect.{IO, IOApp}

object Deadlock extends IOApp.Simple {
  val run =
    for {
      latch <- IO.deferred[Unit]

      body = latch.get
      fiber <- body.start
      _ <- fiber.join

      _ <- latch.complete(())
    } yield ()
}
```

The main fiber is waiting on `fiber.join`, which will only be completed once `latch` is released, which in turn will only happen on the main fiber *after* the child fiber completes. Thus, both fibers are deadlocked on each other. Prior to fiber dumps, this situation would be entirely invisible. Manually traversing the `IO` internals via a heap dump would be the only mechanism for gathering clues as to the problem, which is far from user-friendly and also generally fruitless.

As of Cats Effect 3.3.0, users can now simply trigger a fiber dump to get the following diagnostic output printed to standard error:

```
cats.effect.IOFiber@56824a14 WAITING
 ├ flatMap @ Deadlock$.$anonfun$run$2(Deadlock.scala:26)
 ├ flatMap @ Deadlock$.$anonfun$run$1(Deadlock.scala:25)
 ├ deferred @ Deadlock$.<clinit>(Deadlock.scala:22)
 ├ flatMap @ Deadlock$.<clinit>(Deadlock.scala:22)
 ╰ run$ @ Deadlock$.run(Deadlock.scala:19)
 
cats.effect.IOFiber@6194c61c WAITING
 ├ get @ Deadlock$.$anonfun$run$1(Deadlock.scala:24)
 ╰ get @ Deadlock$.$anonfun$run$1(Deadlock.scala:24)
 
Thread[io-compute-14,5,run-main-group-6] (#14): 0 enqueued
Thread[io-compute-12,5,run-main-group-6] (#12): 0 enqueued
Thread[io-compute-6,5,run-main-group-6] (#6): 0 enqueued
Thread[io-compute-5,5,run-main-group-6] (#5): 0 enqueued
Thread[io-compute-8,5,run-main-group-6] (#8): 0 enqueued
Thread[io-compute-9,5,run-main-group-6] (#9): 0 enqueued
Thread[io-compute-11,5,run-main-group-6] (#11): 0 enqueued
Thread[io-compute-7,5,run-main-group-6] (#7): 0 enqueued
Thread[io-compute-10,5,run-main-group-6] (#10): 0 enqueued
Thread[io-compute-4,5,run-main-group-6] (#4): 0 enqueued
Thread[io-compute-13,5,run-main-group-6] (#13): 0 enqueued
Thread[io-compute-0,5,run-main-group-6] (#0): 0 enqueued
Thread[io-compute-2,5,run-main-group-6] (#2): 0 enqueued
Thread[io-compute-3,5,run-main-group-6] (#3): 0 enqueued
Thread[io-compute-1,5,run-main-group-6] (#1): 0 enqueued
Thread[io-compute-15,5,run-main-group-6] (#15): 0 enqueued
 
Global: enqueued 0, foreign 0, waiting 2
```

A fiber dump prints *every* fiber known to the runtime, regardless of whether they are suspended, blocked, yielding, active on some foreign runtime (via `evalOn`), or actively running on a worker thread. You can see an example of a larger dump in [this gist](https://gist.github.com/06d1cb65d687f489f12ca682e44537e8). Each fiber given a stable unique hexadecimal ID and paired with its status as well as its current trace, making it extremely easy to identify problems such as our earlier deadlock: the first fiber is suspended at line 26 (`fiber.join`) while the second fiber is suspended at line 24 (`latch.get`). This gives us a very good idea of what's happening and how to fix it.

Note that most production applications have a *lot* of fibers at any point in time (millions and even tens of millions are possible even on consumer hardware), so the dump may be quite large. It's also worth noting that this is a *statistical snapshot* mechanism. The data it is aggregating is spread across multiple threads which may or may not have all published into main memory at a given point in time. Thus, it isn't *necessarily* an instantaneously consistent view of the runtime. Under some circumstances, trace information for a given fiber may be behind its actual position, or a fiber may be reported as being in one state (e.g. `YIELDING`) when in fact it is in a different one (e.g. `WAITING`). Under rare circumstances, newly-spawned fibers may be missed. **These circumstances are considerably more common on ARM architectures than they are under x86 due to store order semantics.**

Summary statistics for the global fiber runtime are printed following the fiber traces. In the above example, these statistics are relatively trivial, but in a real-world application this can give you an idea of where your fibers are being scheduled.

## Triggering a fiber dump

Triggering the above fiber dump is a matter of sending a POSIX signal to the process using the `kill` command. The exact signal is dependent on the JVM (and version thereof) and operating system under which your application is running. Rather than attempting to hard-code all possible compatible signal configurations, Cats Effect simply *attempts* to register *both* `INFO` and `USR1` (for JVM applications) or `USR2` (for Node.js applications). In practice, `INFO` will most commonly be used on macOS and BSD, while `USR1` is more common on Linux. Thus, `kill -INFO <pid>` on macOS and `kill -USR1 <pid>` on Linux (or `USR2` for Node.js applications). POSIX signals do not exist on Windows (except under WSL, which behaves exactly like a normal Linux), and thus the mechanism is disabled. Unfortunately it is also disabled on JDK 8, which does not have any free signals available for applications to use.

Since `INFO` is the signal used on macOS and BSD, this combined with a quirk of Apple's TTY implementation means that **anyone running a Cats Effect application on macOS can simply hit <kbd>Ctrl</kbd>-<kbd>T</kbd>** within the active application to trigger a fiber dump, similar to how you can use <kbd>Ctrl</kbd>-<kbd>\\</kbd> to trigger a thread dump. Note that this trick only works on macOS, since that is the only platform which maps a particular keybind to either the `INFO` or `USR1` signals.

In the event that you're either running on a platform which doesn't support POSIX signals, or the signal registration failed for whatever reason, Cats Effect on the JVM will *also* automatically register an [MBean](https://docs.oracle.com/javase/8/docs/technotes/guides/management/overview.html) under `cats.effect.unsafe.metrics.LiveFiberSnapshotTriggerMBean` which can produce a string representation of the fiber dump when its only method is invoked.

## Configuration

Fiber dumps are enabled by default and controlled by the same [configuration as tracing](../core/io-runtime-config.md). Note that there is noticeable overhead for certain workloads, see [#2634](https://github.com/typelevel/cats-effect/issues/2634#issuecomment-1003152586) for discussion), but overall, performance remains very good.

## JavaScript runtimes

And in case you were wondering, yes, it does work on Node.js applications!

```
cats.effect.IOFiber@d WAITING
 
cats.effect.IOFiber@9 WAITING
 ╰ deferred @ <jscode>.null.$c_LDeadlock$(/workspace/cats-effect/example/js/src/main/scala/cats/effect/example/Example.scala:22)
 
cats.effect.IOFiber@a WAITING
 ├ flatMap @ <jscode>.null.<anonymous>(/workspace/cats-effect/example/js/src/main/scala/cats/effect/example/Example.scala:26)
 ├ flatMap @ <jscode>.null.<anonymous>(/workspace/cats-effect/example/js/src/main/scala/cats/effect/example/Example.scala:25)
 ├ deferred @ <jscode>.null.$c_LDeadlock$(/workspace/cats-effect/example/js/src/main/scala/cats/effect/example/Example.scala:22)
 ╰ flatMap @ <jscode>.null.$c_LDeadlock$(/workspace/cats-effect/example/js/src/main/scala/cats/effect/example/Example.scala:22)
 
cats.effect.IOFiber@b WAITING
 
Global: enqueued 0, waiting 4
```

You'll notice that there are two extra fibers in this example. These are actually internal to Cats Effect itself, since `IOApp` on JavaScript maintains a keep-alive fiber and races it against the main fiber. The two fibers in the middle line up pretty closely with those from the JVM dump of the same program, though some of the tracing information is a little different. This is happening because Scala.js itself is very aggressive about inlining across call-sites, and Cats Effect is written in such a way that much of it does get inlined away. This in turn confuses the tracing slightly, since there's simply less information to work with. In practice, this isn't too much of an issue, since most practical fibers will have more than enough tracing information to pin down what's going on, but it's something to keep in mind.

Note that this functionality is *currently* only available on applications running under Node.js, since it isn't entirely clear how to trigger a fiber dump within a browser runtime. If you have thoughts or inspirations on this point, feel free to open an issue or discuss in Discord!
