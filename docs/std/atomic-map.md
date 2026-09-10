---
id: atomic-map
title: Atomic Map
---

A total map from `K` to `AtomicCell[F, V]`.

```scala mdoc:silent
import cats.effect.std.AtomicCell

trait AtomicMap[F[_], K, V] {
  /**
   * Access the AtomicCell for the given key.
   */
  def apply(key: K): AtomicCell[F, V]
}
```

It is conceptually similar to a `AtomicCell[F, Map[K, V]]`, but with better ergonomics when
working on a per key basis. Note, however, that it does not support atomic updates to
multiple keys.

Additionally, it also provide less contention: since all operations are performed on
individual key-value pairs, the pairs can be sharded by key. Thus, multiple concurrent
updates may be executed independently to each other, as long as their keys belong to
different shards.

## Using `AtomicMap`

You can think of a `AtomicMap` like a `MapRef` that supports effectual updates by locking the underlying `Ref`.

```scala mdoc:reset:silent
import cats.effect.IO
import cats.effect.std.AtomicMap

trait State
trait Key

class Service(am: AtomicMap[IO, Key, State]) {
  def modify(key: Key)(f: State => IO[State]): IO[Unit] =
    am(key).evalUpdate(f)
}
```

### Example

Imagine a parking tower,
where users have access to specific floors,
and getting a parking space involves an effectual operation _(e.g. a database call)_.
In that case, it may be better to block than repeat the operation,
but without blocking operations on different floors.

```scala mdoc:reset:silent
import cats.effect.IO
import cats.effect.std.AtomicMap

trait Car
trait Floor
trait ParkingSpace

class ParkingTowerService(state: AtomicMap[IO, Floor, List[ParkingSpace]]) {
  // Tries to park the given Car in the solicited Floor.
  // Returns either the assigned ParkingSpace, or None if this Floor is full.
  def parkCarInFloor(floor: Floor, car: Car): IO[Option[ParkingSpace]] =
    state(key = floor).evalModify {
      case firstFreeParkingSpace :: remainingParkingSpaces =>
        markParkingSpaceAsUsed(parkingSpace = firstFreeParkingSpace, car).as(
          remainingParkingSpaces -> Some(firstFreeParkingSpace)
        )

      case Nil =>
        IO.pure(List.empty -> None)
    }

  private def markParkingSpaceAsUsed(parkingSpace: ParkingSpace, car: Car): IO[Unit] =
    ???
}
```
