---
layout: docs
title:  "Sync"
number: 3
source: "core/shared/src/main/scala/cats/effect/Sync.scala"
scaladoc: "#cats.effect.Sync"
---

# Sync

A `Monad` that can suspend the execution of side effects in the `F[_]` context.

```scala
import cats.MonadError

trait Sync[F[_]] extends MonadError[F, Throwable] {
  def suspend[A](thunk: => F[A]): F[A]
  def delay[A](thunk: => A): F[A] = suspend(pure(thunk))
}
```

This is the most basic interface that represents the suspension of synchronous side effects.

Examples:

```scala
import cats.effect.{IO, Sync}

val sync = Sync[IO].delay(println("Hello world!"))
```

So basically using `Sync[IO].delay` is equivalent to using `IO.apply`.

The use of `suspend` is useful for trampolining (i.e. when the side effect is conceptually the allocation of a stack frame) and it's used by `delay` to represent an internal stack of calls. Any exceptions thrown by the side effect willbe caught and sequenced into the `F[_]` context.
