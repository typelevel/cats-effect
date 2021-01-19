---
id: pqueue
title: priority queue
---

A purely-functional concurrent implementation of a priority queue.

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
