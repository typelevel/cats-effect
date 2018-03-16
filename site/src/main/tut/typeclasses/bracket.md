---
layout: docsplus
title:  "Bracket"
number: 2
source: "core/shared/src/main/scala/cats/effect/Bracket.scala"
scaladoc: "#cats.effect.Bracket"
---

A `Monad` that can acquire, use and release resources in a safe way.

```tut:book:silent
import cats.MonadError

sealed abstract class BracketResult[+E]

trait Bracket[F[_], E] extends MonadError[F, E] {
  def bracket[A, B](acquire: F[A])(use: A => F[B])
    (release: (A, BracketResult[E]) => F[Unit]): F[B]
}
```

**NOTE:** _The current version `0.10` does not include `Bracket` but it will be added in the final release `1.0`. Please refer to the [milestone's roadmap](https://github.com/typelevel/cats-effect/milestones) for more information_.
