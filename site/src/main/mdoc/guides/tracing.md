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
sample program that is running in rabbit mode:

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
how our applications work (coming soon!):

1. Asynchronous stack tracing. This is essentially what is described above,
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

## Usage
Here is a sample program that demonstrates how tracing is instrumented for an 
`IO` program:

```scala
import cats.effect.{ExitCode, IO, IOApp}

object Example extends IOApp {

  def print(msg: String): IO[Unit] =
    IO(println(msg))

  def program2: IO[Unit] =
    for {
      _ <- print("3")
      _ <- print("4")
    } yield ()

  def program: IO[Unit] =
    for {
      _ <- print("1")
      _ <- print("2")
      _ <- IO.shift
      _ <- program2
      _ <- print("5")
    } yield ()

  override def run(args: List[String]): IO[ExitCode] =
    for {
      _     <- IO.suspend(program).traced
      trace <- IO.backtrace
      _     <- trace.compactPrint
    } yield ExitCode.Success

}
```

The tracing mode of an application is controlled by the system property 
`cats.effect.tracing.mode`. There are three tracing modes:
* `DISABLED`: No tracing is performed by the program. Negligible performance hit.
This is the default mode.
* `RABBIT`: Stack traces are collected once and cached for `map`, `flatMap` and
the various `async` combinators. <18% performance hit. This is the recommended 
mode to run in production.
* `SLUG`: Stack traces are collected at every invocation of every `IO` 
combinator. This is the recommended mode to run in development.

TODO: explain the implications and capabilities of each tracing mode in more detail
