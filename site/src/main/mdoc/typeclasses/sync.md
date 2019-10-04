---
layout: docsplus
title:  "Sync"
number: 3
source: "core/shared/src/main/scala/cats/effect/Sync.scala"
scaladoc: "#cats.effect.Sync"
---

A `Monad` that can suspend the execution of side effects in the `F[_]` context.

```scala mdoc:silent
import cats.Defer
import cats.effect.Bracket

trait Sync[F[_]] extends Bracket[F, Throwable] with Defer[F] {
  def suspend[A](thunk: => F[A]): F[A]
  def delay[A](thunk: => A): F[A] = suspend(pure(thunk))
}
```

This is the most basic interface that represents the suspension of synchronous side effects. On the other hand, its implementation of `flatMap` is stack safe, meaning that you can describe `tailRecM` in terms of it as demonstrated in the laws module.

```scala mdoc:reset:silent
import cats.effect.{IO, Sync}
import cats.laws._

val F = Sync[IO]

lazy val stackSafetyOnRepeatedRightBinds = {
  val result = (0 until 10000).foldRight(F.delay(())) { (_, acc) =>
    F.delay(()).flatMap(_ => acc)
  }

  result <-> F.pure(())
}
```

Example of use:

```scala mdoc:reset:silent
import cats.effect.{IO, Sync}

val ioa = Sync[IO].delay(println("Hello world!"))

ioa.unsafeRunSync()
```

So basically using `Sync[IO].delay` is equivalent to using `IO.apply`.

The use of `suspend` is useful for trampolining (i.e. when the side effect is conceptually the allocation of a stack frame) and it's used by `delay` to represent an internal stack of calls. Any exceptions thrown by the side effect will be caught and sequenced into the `F[_]` context.
