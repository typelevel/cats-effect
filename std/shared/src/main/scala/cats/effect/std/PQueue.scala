/*
 * Copyright 2020 Typelevel
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

import cats.implicits._
import cats.effect.kernel.syntax.all._
import cats.effect.kernel.{Concurrent, Deferred, Ref}
import cats.effect.std.internal.BinomialHeap

import scala.collection.immutable.{Queue => ScalaQueue}

/**
 * A purely functional Priority Queue implementation based
 * on a binomial heap (Okasaki)
 *
 * Assumes an `Order` instance is in scope for `A`
 */

abstract class PQueue[F[_], A] { self =>

  /**
   * Enqueues the given element, possibly semantically
   * blocking until sufficient capacity becomes available.
   *
   * O(log(n))
   *
   * @param a the element to be put in the PQueue
   */
  def offer(a: A): F[Unit]

  /**
   * Attempts to enqueue the given element without
   * semantically blocking.
   *
   * O(log(n))
   *
   * @param a the element to be put in the PQueue
   * @return an effect that describes whether the enqueuing of the given
   *         element succeeded without blocking
   */
  def tryOffer(a: A): F[Boolean]

  /**
   * Dequeues the least element from the PQueue, possibly semantically
   * blocking until an element becomes available.
   *
   * O(log(n))
   */
  def take: F[A]

  /**
   * Attempts to dequeue the least element from the PQueue, if one is
   * available without semantically blocking.
   *
   * O(log(n))
   *
   * @return an effect that describes whether the dequeueing of an element from
   *         the PQueue succeeded without blocking, with `None` denoting that no
   *         element was available
   */
  def tryTake: F[Option[A]]

  /**
   * Modifies the context in which this PQueue is executed using the natural
   * transformation `f`.
   *
   * O(1)
   *
   * @return a PQueue in the new context obtained by mapping the current one
   *         using `f`
   */
  def mapK[G[_]](f: F ~> G): PQueue[G, A] =
    new PQueue[G, A] {
      def offer(a: A): G[Unit] = f(self.offer(a))
      def tryOffer(a: A): G[Boolean] = f(self.tryOffer(a))
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
        implicit val Ord = O
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
      F.deferred[Unit].flatMap { offerer =>
        F.uncancelable { poll =>
          ref.modify {
            case State(heap, size, takers, offerers) if takers.nonEmpty =>
              val (taker, rest) = takers.dequeue
              State(heap, size, rest, offerers) -> taker.complete(a).void

            case State(heap, size, takers, offerers) if size < capacity =>
              State(heap.insert(a), size + 1, takers, offerers) -> F.unit

            case s => {
              val State(heap, size, takers, offerers) = s
              val cleanup = ref.update { s =>
                s.copy(offerers = s.offerers.filter(_._2 ne offerer))
              }
              State(heap, size, takers, offerers.enqueue(a -> offerer)) -> poll(offerer.get)
                .onCancel(cleanup)
            }
          }.flatten
        }
      }

    def tryOffer(a: A): F[Boolean] =
      ref
        .modify {
          case State(heap, size, takers, offerers) if takers.nonEmpty =>
            val (taker, rest) = takers.dequeue
            State(heap, size, rest, offerers) -> taker.complete(a).as(true)

          case State(heap, size, takers, offerers) if size < capacity =>
            State(heap.insert(a), size + 1, takers, offerers) -> F.pure(true)

          case s => s -> F.pure(false)
        }
        .flatten
        .uncancelable

    def take: F[A] =
      F.deferred[A].flatMap { taker =>
        F.uncancelable { poll =>
          ref.modify {
            case State(heap, size, takers, offerers) if heap.nonEmpty && offerers.isEmpty =>
              val (rest, a) = heap.take
              State(rest, size - 1, takers, offerers) -> F.pure(a)

            case State(heap, size, takers, offerers) if heap.nonEmpty =>
              val (rest, a) = heap.take
              val ((move, release), tail) = offerers.dequeue
              State(rest.insert(move), size, takers, tail) -> release.complete(()).as(a)

            case State(heap, size, takers, offerers) if offerers.nonEmpty =>
              val ((a, release), rest) = offerers.dequeue
              State(heap, size, takers, rest) -> release.complete(()).as(a)

            case State(heap, size, takers, offerers) =>
              val cleanup = ref.update { s => s.copy(takers = s.takers.filter(_ ne taker)) }
              State(heap, size, takers.enqueue(taker), offerers) ->
                poll(taker.get).onCancel(cleanup)
          }.flatten
        }
      }

    def tryTake: F[Option[A]] =
      ref
        .modify {
          case State(heap, size, takers, offerers) if heap.nonEmpty && offerers.isEmpty =>
            val (rest, a) = heap.take
            State(rest, size - 1, takers, offerers) -> F.pure(a.some)

          case State(heap, size, takers, offerers) if heap.nonEmpty =>
            val (rest, a) = heap.take
            val ((move, release), tail) = offerers.dequeue
            State(rest.insert(move), size, takers, tail) -> release.complete(()).as(a.some)

          case State(heap, size, takers, offerers) if offerers.nonEmpty =>
            val ((a, release), rest) = offerers.dequeue
            State(heap, size, takers, rest) -> release.complete(()).as(a.some)

          case s =>
            s -> F.pure(none[A])
        }
        .flatten
        .uncancelable
  }

  private[std] final case class State[F[_], A](
      heap: BinomialHeap[A],
      size: Int,
      takers: ScalaQueue[Deferred[F, A]],
      offerers: ScalaQueue[(A, Deferred[F, Unit])])

  private[std] object State {
    def empty[F[_], A: Order]: State[F, A] =
      State(
        BinomialHeap.empty[A],
        0,
        ScalaQueue.empty,
        ScalaQueue.empty
      )
  }

  implicit def catsInvariantForPQueue[F[_]: Functor]: Invariant[PQueue[F, *]] = new Invariant[PQueue[F, *]] {
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
      }
  }

  private def assertNonNegative(capacity: Int): Unit =
    if (capacity < 0)
      throw new IllegalArgumentException(
        s"Bounded queue capacity must be non-negative, was: $capacity")
    else ()
}
