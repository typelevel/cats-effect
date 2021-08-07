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

Each evaluation of `unique` is guaranteed to produce a value that is distinct 
from any other currently allocated tokens. 

```scala
val token: F[Unique.Token] = Unique[F].unique
(token, token).mapN { (x, y) => x === y } <-> Monad[F].pure(false)
```

After a token becomes eligible for garbage collection, a subsequent evaluation 
of `unique` may produce a new token with the same hash. Similarly, the guarantee 
of uniqueness only applies within a single JVM or JavaScript runtime. If you 
need a token that is unique across all space and time, use a 
[UUID](https://docs.oracle.com/javase/7/docs/api/java/util/UUID.html) instead.

Both `Sync[F]` and `Spawn[F]` extend `Unique[F]` as both typeclasses trivially
have the ability to create unique values via `delay(new Unique.Token())` and
`start` respectively (fibers are always unique).
