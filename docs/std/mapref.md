---
id: mapref
title: MapRef
---

A total map from a key to a `Ref` of its value.

```scala mdoc:silent
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

Additionally, some implementations also provide less contention,
since all operations are performed over individual key-value pairs,
those could be sharded.
Thus, multiple concurrent updates may be executed independently to each other;
as long as their keys belong to different shards.

### In-Memory database

This is probably one of the most common uses of this tool.

```scala mdoc:reset:silent
//> using lib "org.typelevel::cats-effect:3.4.11"

import cats.effect.IO
import cats.effect.std.MapRef

type Id
type Data

trait DatabaseClient[F[_]] {
  def getDataById(id: Id): F[Option[Data]]
  def upsertData(id: Id, data: Data): F[Unit]
}

object DatabaseClient {
  def inMemory: IO[DatabaseClient[IO]] =
    MapRef.ofShardedImmutableMap[IO, Id, Data](
      shardCount = 5 // Arbitrary number of shards just for demonstration.
    ).map { mapRef =>
      new DatabaseClient[IO] {
        override def getDataById(id: Id): F[Option[Data]] =
          mapRef(id).get

        override def upsertData(id: Id, data: Data): F[Unit] =
          mapRef(id).update(_ => Some(data))
      }
    }
}
```
