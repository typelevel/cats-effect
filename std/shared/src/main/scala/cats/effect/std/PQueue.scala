/*
 * Copyright 2020-2024 Typelevel
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats
package effect
package std

import cats.effect.kernel.{Concurrent, Deferred, Ref}
import cats.effect.kernel.syntax.all._
import cats.effect.std.internal.BinomialHeap
import cats.implicits._

import scala.collection.immutable.{Queue => ScalaQueue}

/**
 * A purely functional Priority Queue implementation based on a binomial heap (Okasaki)
 *
 * Assumes an `Order` instance is in scope for `A`
 */

abstract class PQueue[F[_], A] extends PQueueSource[F, A] with PQueueSink[F, A] { self =>

  /**
   * Modifies the context in which this PQueue is executed using the natural transformation `f`.
   *
   * O(1)
   *
   * @return
   *   a PQueue in the new context obtained by mapping the current one using `f`
   */
  def mapK[G[_]](f: F ~> G): PQueue[G, A] =
    new PQueue[G, A] {
      def offer(a: A): G[Unit] = f(self.offer(a))
      def tryOffer(a: A): G[Boolean] = f(self.tryOffer(a))
      def size: G[Int] = f(self.size)
      val take: G[A] = f(self.take)
      val tryTake: G[Option[A]] = f(self.tryTake)
    }

}

object PQueue {

  def bounded[F[_], A](
      capacity: Int)(implicit F: Concurrent[F], O: Order[A]): F[PQueue[F, A]] = {
    assertNonNegative(capacity)
    F.ref(State.empty[F, A]).map { ref =>
      new PQueueImpl[F, A](ref, capacity) {
        implicit val Ord: Order[A] = O
      }
    }
  }

  def unbounded[F[_], A](implicit F: Concurrent[F], O: Order[A]): F[PQueue[F, A]] =
    bounded(Int.MaxValue)

  private[std] abstract class PQueueImpl[F[_], A](ref: Ref[F, State[F, A]], capacity: Int)(
      implicit F: Concurrent[F])
      extends PQueue[F, A] {
    implicit val Ord: Order[A]

    def offer(a: A): F[Unit] =
      F.uncancelable { poll =>
        F.deferred[Unit].flatMap { offerer =>
          ref.modify {
            case State(heap, size, takers, offerers) if takers.nonEmpty =>
              val (taker, rest) = takers.dequeue
              State(heap.insert(a), size + 1, rest, offerers) -> taker.complete(()).void

            case State(heap, size, takers, offerers) if size < capacity =>
              State(heap.insert(a), size + 1, takers, offerers) -> F.unit

            case s => {
              val State(heap, size, takers, offerers) = s

              val cleanup = ref modify { s =>
                val offerers2 = s.offerers.filter(_ ne offerer)

                if (offerers2.isEmpty) {
                  s.copy(offerers = offerers2) -> F.unit
                } else {
                  val (release, rest) = offerers2.dequeue
                  s.copy(offerers = rest) -> release.complete(()).void
                }
              }

              State(heap, size, takers, offerers.enqueue(offerer)) ->
                (poll(offerer.get) *> poll(offer(a))).onCancel(cleanup.flatten)
            }
          }.flatten
        }
      }

    def tryOffer(a: A): F[Boolean] =
      ref.flatModify {
        case State(heap, size, takers, offerers) if takers.nonEmpty =>
          val (taker, rest) = takers.dequeue
          State(heap.insert(a), size + 1, rest, offerers) -> taker.complete(()).as(true)

        case State(heap, size, takers, offerers) if size < capacity =>
          State(heap.insert(a), size + 1, takers, offerers) -> F.pure(true)

        case s => s -> F.pure(false)
      }

    val take: F[A] =
      F.uncancelable { poll =>
        F.deferred[Unit] flatMap { taker =>
          ref.modify {
            case State(heap, size, takers, offerers) if heap.nonEmpty && offerers.isEmpty =>
              val (rest, a) = heap.take
              State(rest, size - 1, takers, offerers) -> F.pure(a)

            case State(heap, size, takers, offerers) if heap.nonEmpty =>
              val (rest, a) = heap.take
              val (release, tail) = offerers.dequeue
              State(rest, size - 1, takers, tail) -> release.complete(()).as(a)

            case State(heap, size, takers, offerers) =>
              val cleanup = ref modify { s =>
                val takers2 = s.takers.filter(_ ne taker)

                if (takers2.isEmpty) {
                  s.copy(takers = takers2) -> F.unit
                } else {
                  val (release, rest) = takers2.dequeue
                  s.copy(takers = rest) -> release.complete(()).void
                }
              }

              val await = (poll(taker.get) *> poll(take)).onCancel(cleanup.flatten)

              val (fulfill, offerers2) = if (offerers.isEmpty) {
                (await, offerers)
              } else {
                val (release, rest) = offerers.dequeue
                (release.complete(()) *> await, rest)
              }

              State(heap, size, takers.enqueue(taker), offerers2) -> fulfill
          }.flatten
        }
      }

    val tryTake: F[Option[A]] =
      ref.flatModify {
        case State(heap, size, takers, offerers) if heap.nonEmpty && offerers.isEmpty =>
          val (rest, a) = heap.take
          State(rest, size - 1, takers, offerers) -> F.pure(a.some)

        case State(heap, size, takers, offerers) if heap.nonEmpty =>
          val (rest, a) = heap.take
          val (release, tail) = offerers.dequeue
          State(rest, size - 1, takers, tail) -> release.complete(()).as(a.some)

        case s =>
          s -> F.pure(none[A])
      }

    def size: F[Int] =
      ref.get.map(_.size)
  }

  private[std] final case class State[F[_], A](
      heap: BinomialHeap[A],
      size: Int,
      takers: ScalaQueue[Deferred[F, Unit]],
      offerers: ScalaQueue[Deferred[F, Unit]])

  private[std] object State {
    def empty[F[_], A: Order]: State[F, A] =
      State(
        BinomialHeap.empty[A],
        0,
        ScalaQueue.empty,
        ScalaQueue.empty
      )
  }

  implicit def catsInvariantForPQueue[F[_]: Functor]: Invariant[PQueue[F, *]] =
    new Invariant[PQueue[F, *]] {
      override def imap[A, B](fa: PQueue[F, A])(f: A => B)(g: B => A): PQueue[F, B] =
        new PQueue[F, B] {
          override def offer(b: B): F[Unit] =
            fa.offer(g(b))
          override def tryOffer(b: B): F[Boolean] =
            fa.tryOffer(g(b))
          override def take: F[B] =
            fa.take.map(f)
          override def tryTake: F[Option[B]] =
            fa.tryTake.map(_.map(f))
          override def size: F[Int] =
            fa.size
        }
    }

  private def assertNonNegative(capacity: Int): Unit =
    if (capacity < 0)
      throw new IllegalArgumentException(
        s"Bounded queue capacity must be non-negative, was: $capacity")
    else ()
}

trait PQueueSource[F[_], A] extends QueueSource[F, A] {

  /**
   * Attempts to dequeue elements from the PQueue, if they are available without semantically
   * blocking. This is a convenience method that recursively runs `tryTake`. It does not provide
   * any additional performance benefits.
   *
   * @param maxN
   *   The max elements to dequeue. Passing `None` will try to dequeue the whole queue.
   *
   * @return
   *   an effect that contains the dequeued elements from the PQueue
   *
   * Note: If there are multiple elements with least priority, the order in which they are
   * dequeued is undefined.
   */
  override def tryTakeN(maxN: Option[Int])(implicit F: Monad[F]): F[List[A]] =
    QueueSource.tryTakeN[F, A](maxN, tryTake)

}

object PQueueSource {

  implicit def catsFunctorForPQueueSource[F[_]: Functor]: Functor[PQueueSource[F, *]] =
    new Functor[PQueueSource[F, *]] {
      override def map[A, B](fa: PQueueSource[F, A])(f: A => B): PQueueSource[F, B] =
        new PQueueSource[F, B] {
          override def take: F[B] =
            fa.take.map(f)
          override def tryTake: F[Option[B]] =
            fa.tryTake.map(_.map(f))
          override def size: F[Int] =
            fa.size
        }
    }
}

trait PQueueSink[F[_], A] extends QueueSink[F, A] {

  /**
   * Attempts to enqueue the given elements without semantically blocking. If an item in the
   * list cannot be enqueued, the remaining elements will be returned. This is a convenience
   * method that recursively runs `tryOffer` and does not offer any additional performance
   * benefits.
   *
   * @param list
   *   the elements to be put in the PQueue
   * @return
   *   an effect that contains the remaining valus that could not be offered.
   */
  override def tryOfferN(list: List[A])(implicit F: Monad[F]): F[List[A]] =
    QueueSink.tryOfferN(list, tryOffer)

}

object PQueueSink {

  implicit def catsContravariantForPQueueSink[F[_]]: Contravariant[PQueueSink[F, *]] =
    new Contravariant[PQueueSink[F, *]] {
      override def contramap[A, B](fa: PQueueSink[F, A])(f: B => A): PQueueSink[F, B] =
        new PQueueSink[F, B] {
          override def offer(b: B): F[Unit] =
            fa.offer(f(b))
          override def tryOffer(b: B): F[Boolean] =
            fa.tryOffer(f(b))
        }
    }
}
