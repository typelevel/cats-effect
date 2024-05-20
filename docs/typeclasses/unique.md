---
id: unique
title: Unique
---

A typeclass which is a source of unique tokens via `unique`. This is a fairly
low-level programming primitive, and is not usually suitable for identifying
business entities at the level of application logic.

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

This uniqueness guarantee applies only in respect of equality comparison between
`Unique.Token` instances; it is not possible to serialize these instances or
convert them to another form. (The `hashCode` method, for example, is not guaranteed
to return a unique value.) Therefore, these values are meaningful only within a single
application process and cannot (for example) be shared across network boundaries,
persisted to a database, or exposed to end-users. If you need globally unique token
that is suitable for purposes such as these, use a
[UUID](https://docs.oracle.com/javase/8/docs/api/java/util/UUID.html) instead.

Both `Sync[F]` and `Spawn[F]` extend `Unique[F]` as both typeclasses trivially
have the ability to create unique values via `delay(new Unique.Token())` and
`start` respectively (fibers are always unique).
