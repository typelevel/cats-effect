---
id: async-await
title: Async/Await (Experimental)
---

Syntactic sugar that allows for direct-style programming.

## Warning

This feature is provided by an incubating side-library that lives in another repository ([typelevel/cats-effect-cps](https://github.com/typelevel/cats-effect-cps)), so that it can undergo updates at a higher pace than Cats Effect if required.

Because it relies on experimental functionality from the compiler, cats-effect-cps ought to be considered experimental until upstream support stabilises, at which point it will be folded into Cats Effect itself.

## Installation

[![cats-effect-cps Scala version support](https://index.scala-lang.org/typelevel/cats-effect-cps/cats-effect-cps/latest-by-scala-version.svg)](https://index.scala-lang.org/typelevel/cats-effect-cps/cats-effect-cps)


```scala
scalacOptions += "-Xasync" // scala 2.12.12+ / 2.13.3+ (not needed if on Scala 3)
libraryDependencies += "org.typelevel" %% "cats-effect-cps" % "<version>"
```

(see badge above for version number)

## Motivation

A number of programming languages offer this syntax as a solution to the problem commonly known as "callback-hell". Whilst Scala offers a solution to this problem in the form of **for-comprehensions**, which all monadic constructs can integrate with, some people prefer the **async/await** syntax, which sometime helps convey meaning better than for-comprehensions.

## Sequential async/await

This construct works for any effect type that has an associated [Async](../typeclasses/async.md) instance (including but not limited to `IO`, `Resource`, and any combination of those with `EitherT`, `Kleisli`, ...).

```scala
import cats.effect.IO
import cats.effect.cps._

import scala.concurrent.duration._

val io = IO.sleep(50.millis).as(1)

val program: IO[Int] = async[IO] { io.await + io.await }
```

Under the hood, the `async` block is rewritten into non-blocking code that calls onto [Dispatcher](./dispatcher.md) every time `await` is encountered. The end result is lifted into the effect type via callback.

Semantically speaking, the `program` value above is equivalent to

```scala
val program: IO[Int] = for {
  x1 <- io
  x2 <- io
} yield (x1 + x2)
```

### Known limitations

`await` cannot be called from within local methods or lambdas (which prevents its use in `for` loops (that get translated to a `foreach` call)).

```scala
import cats.effect.IO

val program: IO[Int] = async[IO] {
  var n = 0
  for (i <- 1 to 3) { n += IO.pure(i).await } // compile error
  n
}
```

This constraint is implemented in the Scala compiler (not in cats-effect), for good reason : the Scala language does not provide the capability to guarantee that such methods/lambdas will only be called during the runtime lifecycle of the `async` block. These lambdas could indeed be passed to other constructs that would use them asynchronously, thus **escaping** the lifecycle of the `async` block :

```scala
async[IO] {
  // executes asynchronously on a separate thread, completes after 1 second.
  scala.concurrent.Future(IO.sleep(1.second).await)
  // returns instantly, closing the "async" scope
  true
}
``` 

**However**, it is possible to call `await` within an imperative `while` loop:

```scala
import cats.effect.IO
import cats.effect.cps._

val program: IO[Int] = async[IO] {
  var n = 0
  var i = 1

  while (i <= 3) {
    n += IO.pure(i).await
    i += 1
  }

  n
}
```

**NB** as a side note, in the cats ecosystem, the "correct" way to iterate over a collection whilst performing effects is to rely on the `cats.Traverse#traverse` function.
