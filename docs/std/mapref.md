---
id: mapref
title: MapRef
---

A total map from a key to a `Ref` of its value.

```scala mdoc:silent
import cats.effect.Ref

trait MapRef[F[_], K, V] {

  /**
   * Access the reference for this Key
   */
  def apply(k: K): Ref[F, V]
}
```

It is conceptually similar to a `Ref[F, Map[K, V]]`,
but with better ergonomics when working on a per key basis.
Note, however, that it does not support atomic updates to multiple keys.

Additionally, some implementations also provide less contention:
since all operations are performed on individual key-value pairs,
the pairs can be sharded by key.
Thus, multiple concurrent updates may be executed independently to each other,
as long as their keys belong to different shards.

### In-Memory database

This is probably one of the most common uses of this datatype.

```scala mdoc:reset:silent
//> using lib "org.typelevel::cats-effect::3.5.7"

import cats.effect.IO
import cats.effect.std.MapRef

trait DatabaseClient[F[_], Id, Data] {
  def getDataById(id: Id): F[Option[Data]]
  def upsertData(id: Id, data: Data): F[Unit]
}

object DatabaseClient {
  def inMemory[Id, Data]: IO[DatabaseClient[IO, Id, Data]] =
    MapRef.ofShardedImmutableMap[IO, Id, Data](
      shardCount = 5 // Arbitrary number of shards just for demonstration.
    ).map { mapRef =>
      new DatabaseClient[IO, Id, Data] {
        override def getDataById(id: Id): IO[Option[Data]] =
          mapRef(id).get

        override def upsertData(id: Id, data: Data): IO[Unit] =
          mapRef(id).update(_ => Some(data))
      }
    }
}
```
