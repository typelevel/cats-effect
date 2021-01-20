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

TODO once we have `Source` and `Sink` for `Dequeue`
