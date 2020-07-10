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
IOTrace: 13 frames captured, 0 omitted
 ├ flatMap at org.simpleapp.example.Example.run (Example.scala:67)
 ├ flatMap at org.simpleapp.example.Example.program (Example.scala:57)
 ├ flatMap at org.simpleapp.example.Example.program (Example.scala:58)
 ├ flatMap at org.simpleapp.example.Example.program (Example.scala:59)
 ├ flatMap at org.simpleapp.example.Example.program (Example.scala:60)
 ├ async at org.simpleapp.example.Example.program (Example.scala:60)
 ├ flatMap at org.simpleapp.example.Example.program (Example.scala:61)
 ├ flatMap at org.simpleapp.example.Example.program (Example.scala:60)
 ├ flatMap at org.simpleapp.example.Example.program2 (Example.scala:51)
 ├ map at org.simpleapp.example.Example.program2 (Example.scala:52)
 ├ map at org.simpleapp.example.Example.program (Example.scala:60)
 ├ map at org.simpleapp.example.Example.program (Example.scala:62)
 ╰ flatMap at org.simpleapp.example.Example.run (Example.scala:67)
```

However, fiber tracing isn't limited to collecting stack traces. Tracing 
has many use cases that improve developer experience and aid in understanding 
how our applications work. These features are described below. A **bolded name**
indicates that the feature has been merged into master.

1. **Asynchronous stack tracing**. This is essentially what is described above,
where stack frames are collected across asynchronous boundaries for a given
fiber.
2. Combinator inference. Stack traces can be walked to determine what
combinator was actually called by user code. For example, `void` and 
`as` are combinators that are derived from `map`.
3. Intermediate values. The intermediate values that an `IO` program encounters
can be converted to a string to render. This can aid in understanding the
actions that a program takes.
4. Thread tracking. A fiber is scheduled on potentially many threads throughout
its lifetime. Knowing what thread a fiber is running on, and when it shifts
threads is a powerful tool for understanding and debugging the concurrency of 
an application.
5. Tree rendering. By collecting a trace of all `IO` operations, a pretty tree
or graph can be rendered to visualize fiber execution.
6. Fiber identity. Fibers, like threads, are unique and can therefore assume an
identity. If user code can observe fiber identity, powerful observability tools
can be built on top of it. For example, another shortcoming of asynchronous
code is that it becomes tedious to correlate log messages across asynchronous
boundaries (thread IDs aren't very useful). With fiber identity, log messages
produced by a single fiber can be associated with a unique, stable identifier.
7. Fiber ancestry graph. If fibers can assume an identity, an ancestry graph 
can be formed, where nodes are fibers and edges represent a fork/join
relationship.
8. Asynchronous deadlock detection. Even when working with asynchronously
blocking code, fiber deadlocks aren't impossible. Being able to detect
deadlocks or infer when a deadlock can happen makes writing concurrent code
much easier.
9. Live fiber trace dumps. Similar to JVM thread dumps, the execution status 
and trace information of all fibers in an application can be extracted for 
debugging purposes.
10. Monad transformer analysis.

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
size for the buffer is 128, but can be changed via the system property 
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
When full stack tracing is enabled, a stack trace is captured for every
combinator traced in cached mode, but also `pure`, `delay`, `suspend` and other
derived combinators.

This mode will incur a heavy performance hit for most programs, and is
recommended for use in development environments.

### Requesting and rendering traces
Once the global tracing flag is configured, `IO` programs will automatically
begin collecting traces. The trace for a fiber can be accessed at any point
during its execution via the `IO.trace` combinator. This is the `IO` equivalent
of capturing a thread's stack trace.

```scala
import cats.effect.IO

def program: IO[Unit] =
  for {
    _     <- IO(println("Started the program"))
    trace <- IO.trace
  } yield ()
```

After a fiber trace is retrieved, we can print it to the console, just like how 
exception stack traces can be printed with `printStackTrace`. `compactPrint`
includes the most relevant stack trace element for each fiber operation that
was performed. `prettyPrint` includes the entire stack trace for each fiber
operation. These methods accept arguments that lets us customize how traces
are printed.

```scala
import cats.effect.IO

def program: IO[Unit] =
  for {
    _     <- IO(println("Started the program"))
    trace <- IO.trace
    _     <- trace.compactPrint
    _     <- trace.prettyPrint()
  } yield ()
```

Keep in mind that the scope and amount of information that traces hold will
change over time as additional fiber tracing features are merged into master.

### Complete example
Here is a sample program that demonstrates tracing in action.

```scala
// Pass the following system property to your JVM:
// -Dcats.effect.stackTracingMode=full

import cats.implicits._
import cats.effect.{ExitCode, IO, IOApp}
import scala.util.Random

object Example extends IOApp {

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
      _     <- program.handleErrorWith(_ => IO.trace.flatMap(_.compactPrint))
    } yield ExitCode.Success

}
```
