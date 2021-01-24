---
id: dequeue
title: dequeue
---

A purely-functional concurrent implementation of a double-ended queue.

```scala
trait Dequeue[F[_], A] extends Queue[F, A] {

  def offerBack(a: A): F[Unit]

  def tryOfferBack(a: A): F[Boolean]

  def takeBack: F[A]

  def tryTakeBack: F[Option[A]]

  def offerFront(a: A): F[Unit]

  def tryOfferFront(a: A): F[Boolean]

  def takeFront: F[A]

  def tryTakeFront: F[Option[A]]

  def reverse: F[Unit]

  override def offer(a: A): F[Unit] = offerBack(a)

  override def tryOffer(a: A): F[Boolean] = tryOfferBack(a)

  override def take: F[A] = takeFront

  override def tryTake: F[Option[A]] = tryTakeFront

}
```

A `Dequeue` may be constructed as `bounded` or `unbounded`. If bounded then
`offer` may semantically block if the pqueue is already full. `take` is
semantically blocking if the pqueue is empty.

## Variance

`Dequeue` is split into a `DequeueSource` with a `Functor` instance and a
`DequeueSink` with a `Contravariant` functor instance. This allows us to treat a
`Dequeue[F, A]` as a `DequeueSource[F, B]` by mapping with `A => B`  or as a
`DequeueSink[F, B]` by contramapping with `B => A`.

```scala mdoc:reset
import cats.{Contravariant, Functor}
import cats.implicits._
import cats.effect._
import cats.effect.std.{Dequeue, DequeueSource, DequeueSink}
import cats.effect.unsafe.implicits.global

def covariant(list: List[Int]): IO[List[Long]] = (
  for {
    q <- Dequeue.bounded[IO, Int](10)
    qOfLongs: DequeueSource[IO, Long] = Functor[DequeueSource[IO, *]].map(q)(_.toLong)
    _ <- list.traverse(q.offer(_))
    l <- List.fill(list.length)(()).traverse(_ => qOfLongs.take)
  } yield l
)

covariant(List(1,4,2,3)).flatMap(IO.println(_)).unsafeRunSync()

def contravariant(list: List[Boolean]): IO[List[Int]] = (
  for {
    q <- Dequeue.bounded[IO, Int](10)
    qOfBools: DequeueSink[IO, Boolean] =
      Contravariant[DequeueSink[IO, *]].contramap(q)(b => if (b) 1 else 0)
    _ <- list.traverse(qOfBools.offer(_))
    l <- List.fill(list.length)(()).traverse(_ => q.take)
  } yield l
)

contravariant(List(true, false)).flatMap(IO.println(_)).unsafeRunSync()
```
