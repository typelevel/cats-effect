---
layout: docsplus
title:  "SyncIO"
number: 15
source: "core/shared/src/main/scala/cats/effect/SyncIO.scala"
scaladoc: "#cats.effect.SyncIO"
---

A pure abstraction representing the intention to perform a side effect, where the result of that side effect is obtained synchronously.

`SyncIO` is similar to [IO](io.md), but does not support asynchronous computations. Consequently, a `SyncIO` can be run synchronously to obtain a result via `unsafeRunSync`. This is unlike `IO#unsafeRunSync`, which cannot be safely called in general. Doing so on the JVM blocks the calling thread while the async part of the computation is run and doing so on Scala.js throws an exception upon encountering an async boundary.

## Constructing SyncIO values

```scala mdoc
import cats.effect.SyncIO

def putStrLn(str: String): SyncIO[Unit] = SyncIO(println(str))

SyncIO.pure("Cats!").flatMap(putStrLn).unsafeRunSync
```

There's also `suspend` and `unit`, equivalent to the same operations defined in `IO` but with synchronicity guarantees.

## Interoperation with Eval and IO

`SyncIO` defines an `eval` method in its companion object to lift any `cats.Eval` value.

```scala mdoc
import cats.Eval

val eval = Eval.now("hey!")

SyncIO.eval(eval).unsafeRunSync
```

`SyncIO` also defines a `to[F]` method at the class level to lift your value into any `F` with a `LiftIO` instance available.

```scala mdoc
import cats.effect.IO

val ioa: SyncIO[Unit] = SyncIO(println("Hello world!"))
val iob: IO[Unit] = ioa.to[IO]

iob.unsafeRunAsync(_ => ())
```
