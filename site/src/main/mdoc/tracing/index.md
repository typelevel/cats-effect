---
layout: docsplus
title:  "Tracing"
position: 2
---

<nav role="navigation" id="toc"></nav>

## Introduction

Tracing is an advanced feature of `IO` that offers insight into the execution 
graph of a fiber. This unlocks a lot of power for developers in the realm of 
debugging and introspection, not only in local development environments 
but also in critical production settings.

A notable pain point of working with asynchronous code on the JVM is that 
stack traces no longer provide valuable context of the execution path that 
a program takes. This limitation is even more pronounced with Scala's `Future`
(pre- 2.13), where an asynchronous boundary is inserted after each operation. 
`IO`  suffers a similar problem, but even a synchronous `IO` program's stack 
trace is polluted with the details of the `IO` run-loop.

`IO` solves this problem by collecting a stack trace at various `IO` 
operations that a fiber executes, and knitting them together to produce a more 
coherent view of the fiber's execution path. For example, here is a trace of a 
sample program that is running in cached stack tracing mode:

```
IOTrace: 19 frames captured
 ├ flatMap @ org.simpleapp.examples.Main$.program (Main.scala:53)
 ├ map @ org.simpleapp.examples.Main$.foo (Main.scala:46)
 ├ flatMap @ org.simpleapp.examples.Main$.foo (Main.scala:45)
 ├ flatMap @ org.simpleapp.examples.Main$.foo (Main.scala:44)
 ├ flatMap @ org.simpleapp.examples.Main$.foo (Main.scala:43)
 ├ flatMap @ org.simpleapp.examples.Main$.foo (Main.scala:42)
 ├ flatMap @ org.simpleapp.examples.Main$.foo (Main.scala:41)
 ├ flatMap @ org.simpleapp.examples.Main$.foo (Main.scala:40)
 ├ flatMap @ org.simpleapp.examples.Main$.foo (Main.scala:39)
 ├ flatMap @ org.simpleapp.examples.Main$.foo (Main.scala:38)
 ├ flatMap @ org.simpleapp.examples.Main$.foo (Main.scala:37)
 ├ flatMap @ org.simpleapp.examples.Main$.foo (Main.scala:36)
 ├ flatMap @ org.simpleapp.examples.Main$.foo (Main.scala:35)
 ├ flatMap @ org.simpleapp.examples.Main$.foo (Main.scala:34)
 ├ flatMap @ org.simpleapp.examples.Main$.foo (Main.scala:33)
 ├ flatMap @ org.simpleapp.examples.Main$.foo (Main.scala:32)
 ├ flatMap @ org.simpleapp.examples.Main$.program (Main.scala:53)
 ╰ ... (3 frames omitted)
```

However, fiber tracing isn't limited to collecting stack traces. Tracing 
has many use cases that improve developer experience and aid in understanding 
how our applications work. These features are described below. A **bolded name**
indicates that the feature has been merged into master.

1. **Asynchronous stack tracing**. This is essentially what is described above,
where stack frames are collected across asynchronous boundaries for a given
fiber.
2. **Combinator inference**. Stack traces can be walked to determine what
combinator was actually called by user code. For example, `void` and `as` are 
combinators that are derived from `map`, and should appear in the fiber trace
rather than `map`.
3. **Enhanced exceptions**. Exceptions captured by the `IO` runtime can be
augmented with async stack traces to produce more relevant stack traces.
4. Intermediate values. The intermediate values that an `IO` program encounters
can be converted to a string to render. This can aid in understanding the
actions that a program takes.
5. Thread tracking. A fiber is scheduled on potentially many threads throughout
its lifetime. Knowing what thread a fiber is running on, and when it shifts
threads is a powerful tool for understanding and debugging the concurrency of 
an application.
6. Tree rendering. By collecting a trace of all `IO` operations, a pretty tree
or graph can be rendered to visualize fiber execution.
7. Fiber identity. Fibers, like threads, are unique and can therefore assume an
identity. If user code can observe fiber identity, powerful observability tools
can be built on top of it. For example, another shortcoming of asynchronous
code is that it becomes tedious to correlate log messages across asynchronous
boundaries (thread IDs aren't very useful). With fiber identity, log messages
produced by a single fiber can be associated with a unique, stable identifier.
8. Fiber ancestry graph. If fibers can assume an identity, an ancestry graph 
can be formed, where nodes are fibers and edges represent a fork/join
relationship.
9. Asynchronous deadlock detection. Even when working with asynchronously
blocking code, fiber deadlocks aren't impossible. Being able to detect
deadlocks or infer when a deadlock can happen makes writing concurrent code
much easier.
10. Live fiber trace dumps. Similar to JVM thread dumps, the execution status 
and trace information of all fibers in an application can be extracted for 
debugging purposes.
11. Monad transformer analysis.

As note of caution, fiber tracing generally introduces overhead to
applications in the form of higher CPU usage, memory and GC pressure. 
Always remember to performance test your applications with tracing enabled 
before deploying it to a production environment! 

## Asynchronous stack tracing
### Configuration
The stack tracing mode of an application is configured by the system property
`cats.effect.stackTracingMode`. There are three stack tracing modes: `DISABLED`,
`CACHED` and `FULL`. These values are case-insensitive.

To prevent unbounded memory usage, stack traces for a fiber are accumulated 
in an internal buffer as execution proceeds. If more traces are collected than
the buffer can retain, then the older traces will be overwritten. The default
size for the buffer is 32, but can be changed via the system property 
`cats.effect.traceBufferSize`. Keep in mind that the buffer size will always
be rounded up to a power of 2.

For example, to enable full stack tracing and a trace buffer size of 1024,
specify the following system properties:
```
-Dcats.effect.stackTracingMode=full -Dcats.effect.traceBufferSize=1024
```

#### DISABLED
No tracing is instrumented by the program and so incurs negligible impact to
performance. If a trace is requested, it will be empty.

#### CACHED
When cached stack tracing is enabled, a stack trace is captured and cached for
every `map`, `flatMap` and `async` call in a program. 

The stack trace cache is indexed by the lambda class reference, so cached
tracing may produce inaccurate fiber traces under several scenarios:
1. Monad transformer composition
2. A named function is supplied to `map`, `async` or `flatMap` at multiple
call-sites

We measured less than a 30% performance hit when cached tracing is enabled
for a completely synchronous `IO` program, but it will most likely be much less
for any program that performs any sort of I/O. We strongly recommend 
benchmarking applications that make use of tracing.

This is the recommended mode to run in most production applications and is 
enabled by default.

#### FULL
When full stack tracing is enabled, a stack trace is captured for most `IO`
combinators including `pure`, `delay`, `suspend`, `raiseError` as well as those
traced in cached mode. 

Stack traces are collected *on every invocation*, so naturally most programs
will experience a significant performance hit. This mode is mainly useful for
debugging in development environments.

### Requesting and printing traces
Once the global tracing flag is configured, `IO` programs will automatically
begin collecting traces. The trace for a fiber can be accessed at any point
during its execution via the `IO.trace` combinator. This is the `IO` equivalent
of capturing a thread's stack trace.

After we have a fiber trace, we can print it to the console, not unlike how
Java exception stack traces are printed with `printStackTrace`. `printFiberTrace`
can be called to print fiber traces to the consoles. Printing behavior can be 
customized by passing in a `PrintingOptions` instance. By default, a fiber trace 
is rendered in a very compact presentation that includes the most relevant stack 
trace element from each fiber operation.

```scala
import cats.effect.IO

def program: IO[Unit] =
  for {
    _     <- IO(println("Started the program"))
    trace <- IO.trace
    _     <- trace.printFiberTrace()
  } yield ()
```

Keep in mind that the scope and amount of information that traces hold will
change over time as additional fiber tracing features are merged into master.

## Enhanced exceptions
The stack trace of an exception caught by the IO runloop looks similar to the
following output:
```
java.lang.Throwable: A runtime exception has occurred
	at org.simpleapp.examples.Main$.b(Main.scala:28)
	at org.simpleapp.examples.Main$.a(Main.scala:25)
	at org.simpleapp.examples.Main$.$anonfun$foo$11(Main.scala:37)
	at scala.runtime.java8.JFunction0$mcV$sp.apply(JFunction0$mcV$sp.scala:18)
	at cats.effect.internals.IORunLoop$.cats$effect$internals$IORunLoop$$loop(IORunLoop.scala:103)
	at cats.effect.internals.IORunLoop$RestartCallback.signal(IORunLoop.scala:440)
	at cats.effect.internals.IORunLoop$RestartCallback.apply(IORunLoop.scala:461)
	at cats.effect.internals.IORunLoop$RestartCallback.apply(IORunLoop.scala:399)
	at cats.effect.internals.IOShift$Tick.run(IOShift.scala:36)
	at cats.effect.internals.PoolUtils$$anon$2$$anon$3.run(PoolUtils.scala:52)
	at java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1149)
	at java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:624)
	at java.lang.Thread.run(Thread.java:748)
```

It includes stack frames that are part of the IO runloop, which are generally
not of interest to users of the library. When asynchronous stack tracing is
enabled, the IO runloop is capable of augmenting the stack traces of caught
exceptions to include frames from the asynchronous stack traces. For example,
the augmented version of the above stack trace looks like the following:
```
java.lang.Throwable: A runtime exception has occurred
	at org.simpleapp.examples.Main$.b(Main.scala:28)
	at org.simpleapp.examples.Main$.a(Main.scala:25)
	at org.simpleapp.examples.Main$.$anonfun$foo$11(Main.scala:37)
	at map @ org.simpleapp.examples.Main$.$anonfun$foo$10(Main.scala:37)
	at flatMap @ org.simpleapp.examples.Main$.$anonfun$foo$8(Main.scala:36)
	at flatMap @ org.simpleapp.examples.Main$.$anonfun$foo$6(Main.scala:35)
	at flatMap @ org.simpleapp.examples.Main$.$anonfun$foo$4(Main.scala:34)
	at flatMap @ org.simpleapp.examples.Main$.$anonfun$foo$2(Main.scala:33)
	at flatMap @ org.simpleapp.examples.Main$.foo(Main.scala:32)
	at flatMap @ org.simpleapp.examples.Main$.program(Main.scala:42)
	at as @ org.simpleapp.examples.Main$.run(Main.scala:48)
	at main$ @ org.simpleapp.examples.Main$.main(Main.scala:22)
```

Note that the relevant stack frames from the call-site of the user code
is preserved, but all IO-related stack frames are replaced with async
stack trace frames.

This feature is controlled by the system property 
`cats.effect.enhancedExceptions`. It is enabled by default.

```
-Dcats.effect.stackTracingMode=false
```

### Complete example
Here is a sample program that demonstrates tracing in action.

```scala
// Pass the following system property to your JVM:
// -Dcats.effect.stackTracingMode=full

import cats.effect.tracing.PrintingOptions
import cats.implicits._
import cats.effect.{ExitCode, IO, IOApp}

import scala.util.Random

object Example extends IOApp {

  val options = PrintingOptions.Default
    .withShowFullStackTraces(true)
    .withMaxStackTraceLines(8)

  def fib(n: Int, a: Long = 0, b: Long = 1): IO[Long] =
    IO(a + b).flatMap { b2 =>
      if (n > 0)
        fib(n - 1, b, b2)
      else
        IO.pure(b2)
    }
  
  def program: IO[Unit] =
    for {
      x <- fib(20)
      _ <- IO(println(s"The 20th fibonacci number is $x"))
      _ <- IO(Random.nextBoolean()).ifM(IO.raiseError(new Throwable("")), IO.unit)
    } yield ()

  override def run(args: List[String]): IO[ExitCode] =
    for {
      _ <- program.handleErrorWith(_ => IO.trace.flatMap(_.printFiberTrace(options)))
    } yield ExitCode.Success

}
```
