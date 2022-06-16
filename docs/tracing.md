---
id: tracing
title: Tracing
---

## Introduction

Tracing is an advanced feature of `IO` that offers insights into the execution
graph of a fiber. This unlocks a lot of power for developers in the realm of
debugging and introspection, not only in local development environments, but
also in critical production settings.

A notable pain point of working with asynchronous code on the JVM is that stack
traces no longer provide valuable context of the execution path that a program
takes. This limitation is even more pronounced with Scala's `Future`
(pre- 2.13), where an asynchronous boundary is inserted after each operation.
As an inherently asynchronous data type, `IO` suffers a similar problem, but
even the stack trace of an `IO` program executed completely synchronously is
poluted with the implementation methods of the `IO` run-loop, trace information
which is unnecessary and confusing for the end-user application.

`IO` solves this problem by collecting a stack trace at various `IO` operations
that a fiber executes during its lifetime, and knitting them together to produce
a more coherent view of the fiber's execution path.

However, fiber tracing isn't limited to just collecting and assembling
meaningful stack traces:

1. **Asynchronous stack tracing**. This is essentially what is described above,
where stack frames are collected across asynchronous boundaries for a given
fiber.
2. **Constructor/combinator inference**. Stack traces can be walked to determine
what specific `IO` constructor or combinator was actually called by user code.
For example, `void` and `as` are combinators that are derived from `map`, and
should appear in the fiber trace rather than `map`. The same goes for `IO`
constructors such as `async`, which is a complex composition of many lower level
implementation details, none of which are present in the final stack trace, as
they are not relevant to the user.
3. **JVM and GC safe trace information cache**. The trace information caching
mechanism of Cats Effect was completely rewritten in version 3.2 to take
advantage of the `java.lang.ClassValue` platform APIs, a JVM-native caching
solution with several appealing properties:
   - Class-loader safety -- long-running Cats Effect applications fully respect
   dynamic class-loading environments, promptly releasing references to unloaded
   classes when instructed by the JVM runtime, thus helping with GC.
   - Lock-free caching data structures natively optimized by the JIT compiler
   for maximum performance. During the development of the cached asynchronous
   stack tracing feature for Cats Effect, it was measured that the switch to the
   JVM platform APIs resulted in a 5-10% (application dependent) performance
   improvement compared to the previous tracing cache implementation.
4. **Improvements to the full stack tracing performance**. With Cats Effect 3
being a complete code rewrite of the project as a whole, we were able to revisit
and improve on specific details of the previous tracing implementation.
The development cycle of the asynchronous tracing feature took slightly longer
than initially planned, mostly because it was preceded by a significant
experimentation period. During this experimentation period, we were able to
drastically optimize the process of stack tracing information collection, which
now operates much more closely to the JVM potential. This resulted in up to 5x
improvements compared to the previous collection mechanism. As impressive as
these numbers are, the reality is that real-world applications with cached
asynchronous stack tracing enabled (which is the default mode of operation) will
not see meaningful performance improvements from this change, simply because the
collection of traces before caching them is only a tiny chunk of the lifecycle
of a real long-running process. However, we feel that this is still a worthwhile
achievement, as uncached (full) stack tracing is much more performant, which
will result in a noticeably improved developer experience when debugging `IO`
programs.
5. **Augmented exceptions**. Exceptions arising in user code are caught by the
Cats Effect runtime, stripped of unnecessary internal implementation details and
augmented using asynchronous stack tracing information gathered at runtime, such
as information about traced `IO` constructors and combinators, before finally
being presented to the user.

### General note on stack tracing performance

As a note of caution, fiber tracing generally introduces overhead to
applications in the form of higher CPU usage, memory and GC pressure. Always
remember to performance test your specific application with and without tracing
enabled before deploying it to a production environment!

## Asynchronous stack tracing

### Configuration
The stack tracing mode of an application is configured by the JVM system property
`cats.effect.tracing.mode` (environment variable `CATS_EFFECT_TRACING_MODE` for JS). There are 3 stack tracing modes: `none`,
`cached` and `full`. These values are case-insensitive. The default tracing mode
is `cached`, which uses a global cache to avoid expensive recomputation of stack
tracing information.

To prevent unbounded memory usage, stack tracing information for a given fiber
is accumulated in an internal buffer as execution proceeds. If more traces are
collected than the buffer can retain, the oldest trace information will be
overwritten. The default size of the buffer is 16. This value can be configured
via the JVM system property `cats.effect.tracing.buffer.size` (environment variable `CATS_EFFECT_TRACING_BUFFER_SIZE` for JS). Keep in mind that the
value configured by the system property must be a power of two and will be rounded to the nearest power if not.

For example, to enable full stack tracing and a trace buffer of size 1024,
specify the following JVM system properties:

```
-Dcats.effect.tracing.mode=full -Dcats.effect.tracing.buffer.size=1024
```

For instructions how to configure these settings on JS see the [`IORuntime` configuration page](core/io-runtime-config.md).
Additionally, we recommend installing the npm package [source-map-support](https://www.npmjs.com/package/source-map-support).
This will use the [source map](https://nodejs.medium.com/source-maps-in-node-js-482872b56116) to create stack traces with line numbers for Scala code (and not line numbers for generated JS code, which are not so helpful!).
Note that tracing is currently only supported on Scala.js in `fastLinkJS` (aka `fastOptJS`) mode.
For performance, tracing is completely disabled in `fullLinkJS` (aka `fullOptJS`) and the relevant code is eliminated from the emitted JS.

## Stack tracing modes

### `none`
Tracing is fully disabled and all performance impacts are negated. Outside of
some extremely small (~8-16 bytes) object footprint differences, this should be
exactly identical to a hypothetical `IO` run-loop which lacks any support for
tracing.

### `cached`
When cached stack tracing is enabled, a stack trace is captured and cached for
each `map`, `flatMap`, `async`, etc. call in a program.

_Caution_: The stack trace cache is indexed by the lambda class reference, so
cached tracing may produce inaccurate fiber traces in these circumstances:
1. Monad transformer composition
2. When a named function is supplied at multiple call-sites

The performance impact of cached stack tracing was measured to be less than 30%
in synthetic benchmarks for tightly looping `IO` programs consisting of a
combination of both synchronous and asynchronous combinators (in Cats Effect 3,
all `IO` programs are asynchronous in nature). The actual performance impact of
cached tracing will be much lower for any program that performs any sort of I/O.
Again, we strongly recommend benchmarking applications that make use of tracing,
in order to avoid surprises and to get a realistic picture of the specific
impact on your application.

It's also worth qualifying the above numbers slightly. A "30% performance
degradation" in this case is defined relative to `IO`'s evaluation *without any
support for tracing*. This performance bar is extremely high, meaning that the
absolute (as opposed to relative) difference between "tracing" and "no tracing
at all" is measured in micro- and nanoseconds in most cases. To put all of this
in perspective, even with tracing, `IO`'s evaluation is still multiple orders of
magnitude faster than the equivalent program written using Scala's `Future` when
measured using the same highly-synthetic benchmarks. All of which is not to say
that `Future` is slow, but rather that these benchmarks are attempting to
magnify even the slightest differences in `IO`'s performance and are not
representative of most real-world scenarios.

When tracing was first introduced in Cats Effect 2, its absolute performance
impact was considerably higher than the impact of the rewritten implementation
in Cats Effect 3.2. Despite this, even extremely performance-sensitive real
world applications subjected to intensive benchmarking and stress testing did
not observe differences in top-line metrics.

It is for this reason that `cached` tracing is on by default, and is the
recommended mode even when running in production.

### `full`
As with cached stack tracing, when full stack tracing is enabled, a stack trace
is captured for most `IO` combinators and constructors.

The difference comes in the fact that stack traces are collected on
*every invocation*, so naturally, most programs will experience a significant
performance hit. This mode is mainly useful for debugging in development
environments.

## Enhanced exceptions
Without tracing, the stack trace of an exception caught by the `IO` runtime
often looks similar to the following:
```
Exception in thread "main" java.lang.Throwable: Boom!
        at Example$.$anonfun$program$5(Main.scala:23)
	    at cats.effect.IO.$anonfun$ifM$1(IO.scala:409)
	    at cats.effect.IOFiber.runLoop(IOFiber.scala:383)
	    at cats.effect.IOFiber.execR(IOFiber.scala:1126)
	    at cats.effect.IOFiber.run(IOFiber.scala:125)
	    at cats.effect.unsafe.WorkerThread.run(WorkerThread.scala:359)
```

It includes stack frames that are part of the `IO` runtime, which are generally
not of interest to application developers. When asynchronous stack tracing is
enabled, the `IO` runtime is capable of augmenting the stack traces of caught
exceptions to include frames from the asynchronous stack traces. For example,
the augmented version of the stack trace from above looks like the following:
```
Exception in thread "main" java.lang.Throwable: Boom!
        at Example$.$anonfun$program$5(Main.scala:25)
        at apply @ Example$.$anonfun$program$3(Main.scala:24)
        at ifM @ Example$.$anonfun$program$3(Main.scala:25)
        at map @ Example$.$anonfun$program$3(Main.scala:24)
        at apply @ Example$.$anonfun$program$1(Main.scala:23)
        at flatMap @ Example$.$anonfun$program$1(Main.scala:23)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at apply @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.fib(Main.scala:13)
        at flatMap @ Example$.program(Main.scala:22)
        at run$ @ Example$.run(Main.scala:10)
```

Note that the relevant stack frames from the call-site of the user code are
preserved, but all `IO` runtime related stack frames are replaced with async
stack frame traces.

The example above shows that a lot of information can be retained even for
deeply nested recursive `IO` programs like the one on this page, even with a
fairly small buffer. The example was generated using the following stack tracing
configuration:
```
-Dcats.effect.tracing.mode=full -Dcats.effect.tracing.buffer.size=64
```

The enhanced exceptions feature is controlled by the system property
`cats.effect.tracing.exceptions.enhanced`. It is enabled by default.

It can be disabled with the following configuration:
```
-Dcats.effect.tracing.exceptions.enhanced=false
```

### Complete code
Here is the code snippet that was used to generate the above examples:
```scala mdoc
// Pass the following system property to your JVM:
// -Dcats.effect.tracing.mode=full
// -Dcats.effect.tracing.buffer.size=64

import cats.effect.{IO, IOApp}

import scala.util.Random

object Example extends IOApp.Simple {

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
      _ <- IO(Random.nextBoolean())
        .ifM(IO.raiseError(new Throwable("Boom!")), IO.unit)
    } yield ()

  override def run: IO[Unit] =
    program
}
```
