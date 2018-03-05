---
layout: docs
title:  "IO"
number: 6
source: "shared/src/main/scala/cats/effect/IO.scala"
scaladoc: "#cats.effect.IO"
---
# IO

A pure abstraction representing the intention to perform a side effect, where the result of that side effect may be obtained synchronously (via return) or asynchronously (via callback).

Effects contained within this abstraction are not evaluated until the "end of the world", which is to say, when one of the "unsafe" methods are used. Effectful results are not memoized, meaning that memory overhead is minimal (and no leaks), and also that a single effect may be run multiple times in a referentially-transparent manner. For example:

```tut:book
import cats.effect.IO

val ioa = IO { println("hey!") }

val program: IO[Unit] =
  for {
     _ <- ioa
     _ <- ioa
  } yield ()

program.unsafeRunSync()
```

The above example prints "hey!" twice, as the effect re-runs each time it is sequenced in the monadic chain.

`IO` is trampolined for all `synchronous` joins. This means that you can safely call `flatMap` in a recursive function of arbitrary depth, without fear of blowing the stack. However, `IO` cannot guarantee stack-safety in the presence of arbitrarily nested asynchronous suspensions. This is quite simply because it is "impossible" (on the JVM) to guarantee stack-safety in that case. For example:

```tut:book
import cats.effect.IO

def lie[A]: IO[A] = IO.async[A](cb => cb(Right(lie.unsafeRunSync)))
```

This should blow the stack when evaluated. Also note that there is no way to encode this using `tailRecM` in such a way that it does "not" blow the stack. Thus, the `tailRecM` on `Monad[IO]` is not guaranteed to produce an `IO` which is stack-safe when run, but will rather make every attempt to do so barring pathological structure.

`IO` makes no attempt to control finalization or guaranteed resource-safety in the presence of concurrent preemption, simply because `IO` does not care about concurrent preemption at all! `IO` actions are not interruptible and should be considered broadly-speaking atomic, at least when used purely.
