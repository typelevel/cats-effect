---
id: pqueue
title: priority queue
---

A purely-functional concurrent implementation of a priority queue, using
a cats `Order` instance to determine the relative priority of elements.

```scala
trait PQueue[F[_], A : Order] {

  def take: F[A]

  def tryTake: F[Option[A]]

  def offer(a: A): F[Unit]

  def tryOffer(a: A): F[Boolean]

}
```

A `PQueue` may be constructed as `bounded` or `unbounded`. If bounded then
`offer` may semantically block if the pqueue is already full. `take` is
semantically blocking if the pqueue is empty.

```scala mdoc
import cats.Order
import cats.implicits._
import cats.effect._
import cats.effect.std.PQueue
import cats.effect.unsafe.implicits.global

val list = List(1,4,3,7,5,2,6,9,8)

implicit val orderForInt: Order[Int] = Order.fromLessThan((x, y) => x < y)

def absurdlyOverengineeredSort(list: List[Int]) = (
  for {
    pq <- PQueue.bounded[IO, Int](10)
    _ <- list.traverse(pq.offer(_))
    l <- List.fill(list.length)(()).traverse( _ => pq.take)
  } yield l
)

absurdlyOverengineeredSort(list).flatMap(IO.println(_)).unsafeRunSync()
```

## Variance

`PQueue` is split into a `PQueueSource` with a `Functor` instance and a
`PQueueSink` with a `Contravariant` functor instance. This allows us to
treat a `PQueue[F, A]` as a `PQueueSource[F, B]` by mapping with `A => B` 
or as a `PQueueSink[F, B]` by contramapping with `B => A`.

```scala mdoc:reset
import cats.Order
import cats.{Contravariant, Functor}
import cats.implicits._
import cats.effect._
import cats.effect.std.{PQueue, PQueueSource, PQueueSink}
import cats.effect.unsafe.implicits.global

implicit val orderForInt: Order[Int] = Order.fromLessThan((x, y) => x < y)

def covariant(list: List[Int]): IO[List[Long]] = (
  for {
    pq <- PQueue.bounded[IO, Int](10)
    pqOfLongs: PQueueSource[IO, Long] = Functor[PQueueSource[IO, *]].map(pq)(_.toLong)
    _ <- list.traverse(pq.offer(_))
    l <- List.fill(list.length)(()).traverse(_ => pqOfLongs.take)
  } yield l
)

covariant(List(1,4,2,3)).flatMap(IO.println(_)).unsafeRunSync()

def contravariant(list: List[Boolean]): IO[List[Int]] = (
  for {
    pq <- PQueue.bounded[IO, Int](10)
    pqOfBools: PQueueSink[IO, Boolean] =
      Contravariant[PQueueSink[IO, *]].contramap(pq)(b => if (b) 1 else 0)
    _ <- list.traverse(pqOfBools.offer(_))
    l <- List.fill(list.length)(()).traverse(_ => pq.take)
  } yield l
)

contravariant(List(true, false)).flatMap(IO.println(_)).unsafeRunSync()
```
