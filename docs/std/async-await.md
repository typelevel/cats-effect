---
id: async-await
title: Async/Await (Experimental)
---

Syntactic sugar that allows for direct-style programming.

## Warning

This feature currently only works on scala 2 (2.12.12+ / 2.13.3+), relies on an experimental compiler feature enabled by the `-Xasync` scalac option, and should be considered unstable with regards to backward compatibility guarantees (be that source or binary). It does however work on JVM and JS runtimes.

## Motivation

A number of programming languages offer this syntax as a solution to the problem commonly known as "callback-hell". Whilst Scala offers a solution to this problem in the form of **for-comprehensions**, which all monadic constructs can integrate with, some people prefer the **async/await** syntax, which sometime helps convey meaning better than for-comprehensions.

## Sequential async/await

This construct works for any effect type that has an associated [Async](../typeclasses/async.md) instance (including but not limited to `IO`, `Resource`, and any combination of those with `EitherT`, `Kleisli`, ...).

```scala mdoc:compile-only
import cats.effect.IO
import cats.effect.std.AsyncAwaitDsl

object dsl extends AsyncAwaitDsl[IO]
import dsl._

import scala.concurrent.duration._

val io = IO.sleep(50.millis).as(1)
val program : IO[Int] = async { await(io) + await(io) }
```

Under the hood, the `async` block is rewritten into non-blocking code that calls onto [Dispatcher](./dispatcher.md) every time `await` is encountered. The end result is lifted into the effect type via callback.

Semantically speaking, the `program` value above is equivalent to

```scala
val program : IO[Int] = for {
  x1 <- io
  x2 <- io
} yield (x1 + x2)
```

### Known limitations

`await` cannot be called from within local methods or lambdas (which prevents its use in `for` loops (that get translated to a `foreach` call)). This is due to a limitation in the Scala compiler.

```scala mdoc:reset:fail
import cats.effect.IO
import cats.effect.std.AsyncAwaitDsl

object dsl extends AsyncAwaitDsl[IO]
import dsl._

val program : IO[Int] = async {
  var n = 0
  for (i <- 1 to 3) (n += await(IO.pure(i)) )
  n
}
```

It does however work in while loops :

```scala mdoc:compile-only
import cats.effect.IO

object dsl extends cats.effect.std.AsyncAwaitDsl[IO]
import dsl._

val program : IO[Int] = async {
  var n = 0
  var i = 1

  while(i <= 3){
    n += await(IO.pure(i))
    i += 1
  }

  n
}
```

