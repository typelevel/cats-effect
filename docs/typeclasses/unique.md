---
id: unique
title: Unique
---

A typeclass which is a source of unique tokens via `unique`

```scala
trait Unique[F[_]] {
  def unique: F[Unique.Token]
}
```

`unique` is guaranteed to produce a distinct value every time it is evaluated

```scala
val token: F[Uniqe.Token] = Unique[F].unique
(token, token).mapN { (x, y) => x === y } <-> Monad[F].pure(false)
```
