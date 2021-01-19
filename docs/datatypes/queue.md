---
id: queue
title: queue
---

A purely-functional concurrent implementation of a queue.

```scala
trait Queue[F[_], A] {

  def take: F[A]

  def tryTake: F[Option[A]]

  def offer(a: A): F[Unit]

  def tryOffer(a: A): F[Boolean]

}
```

`take` is semantically blocking when the queue is empty. A `Queue` may be constructed
with different policies for the behaviour of `offer` when the queue has reached
capacity:
- `bounded(capacity: Int)`: `offer` is semantically when the queue is full
- `synchronous`: equivalent to `bounded(0)` - `offer` and `take` are both blocking
  until another fiber invokes the opposite action
- `unbounded`: `offer` never blocks
- `dropping(capacity: Int)`: `offer` never blocks but new elements are discarded if the
  queue is full
- `circularBuffer(capacity: Int)`: `offer` never blocks but the oldest elements are discarded
  in favour of new elements when the queue is full

## Variance

`Queue` is split into a `QueueSource` with a `Functor` instance and a
`QueueSink` with a contravariant functor instance. This allows us to
treat a `Queue[F, A]` as a `Source[F, B]` by mapping with `A => B` 
or as a `Sink[F, B]` by contramapping with `B => A`.

```scala mdoc:reset
import cats.{Contravariant, Functor}
import cats.implicits._
import cats.effect._
import cats.effect.std.{Queue, QueueSource, QueueSink}
import cats.effect.unsafe.implicits.global

def covariant(list: List[Int]): IO[List[Long]] = (
  for {
    q <- Queue.bounded[IO, Int](10)
    qOfLongs: QueueSource[IO, Long] = Functor[QueueSource[IO, *]].map(q)(_.toLong)
    _ <- list.traverse(q.offer(_))
    l <- List.fill(list.length)(()).traverse(_ => qOfLongs.take)
  } yield l
)

covariant(List(1,4,2,3)).flatMap(IO.println(_)).unsafeRunSync()

def contravariant(list: List[Boolean]): IO[List[Int]] = (
  for {
    q <- Queue.bounded[IO, Int](10)
    qOfBools: QueueSink[IO, Boolean] =
      Contravariant[QueueSink[IO, *]].contramap(q)(b => if (b) 1 else 0)
    _ <- list.traverse(qOfBools.offer(_))
    l <- List.fill(list.length)(()).traverse(_ => q.take)
  } yield l
)

contravariant(List(true, false)).flatMap(IO.println(_)).unsafeRunSync()
```

