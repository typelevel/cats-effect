---
layout: docsplus
title:  "Bracket"
number: 2
source: "core/shared/src/main/scala/cats/effect/Bracket.scala"
scaladoc: "#cats.effect.Bracket"
---

`Bracket` is an extension of `MonadError` exposing the `bracket`
operation, a generalized abstracted pattern of safe resource
acquisition and release in the face of errors or interruption.

Important note, throwing in `release` function is undefined since the behavior is left to the
concrete implementations (ex. cats-effect `Bracket[IO]`, Monix `Bracket[Task]` or ZIO).

```scala mdoc:silent
import cats.MonadError

sealed abstract class ExitCase[+E]

trait Bracket[F[_], E] extends MonadError[F, E] {

  def bracketCase[A, B](acquire: F[A])(use: A => F[B])
    (release: (A, ExitCase[E]) => F[Unit]): F[B]
   
  // Simpler version, doesn't distinguish b/t exit conditions
  def bracket[A, B](acquire: F[A])(use: A => F[B])
    (release: A => F[Unit]): F[B]
}
```

