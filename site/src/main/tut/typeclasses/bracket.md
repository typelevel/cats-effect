---
layout: docs
title:  "Bracket"
number: 2
source: "core/shared/src/main/scala/cats/effect/Bracket.scala"
scaladoc: "#cats.effect.Bracket"
---

# Bracket

A `Monad` that can acquire, use and release resources in a safe way.

```tut:book:silent
import cats.MonadError

sealed abstract class BracketResult[+E]

trait Bracket[F[_], E] extends MonadError[F, E] {
  def bracket[A, B](acquire: F[A])(use: A => F[B])
    (release: (A, BracketResult[E]) => F[Unit]): F[B]
}
```
