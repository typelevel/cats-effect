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

package cats.effect.std

import cats.~>
import cats.effect.kernel.{Deferred, GenConcurrent, Poll, Ref}
import cats.effect.kernel.syntax.all._
import cats.syntax.all._

import scala.collection.immutable.{Queue => ScalaQueue}

/**
 * A purely functional, concurrent data structure which allows insertion and
 * retrieval of elements of type `A` in a first-in-first-out (FIFO) manner.
 *
 * Depending on the type of queue constructed, the [[Queue#offer]] operation can
 * block semantically until sufficient capacity in the queue becomes available.
 *
 * The [[Queue#take]] operation semantically blocks when the queue is empty.
 *
 * The [[Queue#tryOffer]] and [[Queue#tryTake]] allow for usecases which want to
 * avoid semantically blocking a fiber.
 */
abstract class Queue[F[_], A] { self =>

  /**
   * Enqueues the given element at the back of the queue, possibly semantically
   * blocking until sufficient capacity becomes available.
   *
   * @param a the element to be put at the back of the queue
   */
  def offer(a: A): F[Unit]

  /**
   * Attempts to enqueue the given element at the back of the queue without
   * semantically blocking.
   *
   * @param a the element to be put at the back of the queue
   * @return an effect that describes whether the enqueuing of the given
   *         element succeeded without blocking
   */
  def tryOffer(a: A): F[Boolean]

  /**
   * Dequeues an element from the front of the queue, possibly semantically
   * blocking until an element becomes available.
   */
  def take: F[A]

  /**
   * Attempts to dequeue an element from the front of the queue, if one is
   * available without semantically blocking.
   *
   * @return an effect that describes whether the dequeueing of an element from
   *         the queue succeeded without blocking, with `None` denoting that no
   *         element was available
   */
  def tryTake: F[Option[A]]

  /**
   * Modifies the context in which this queue is executed using the natural
   * transformation `f`.
   *
   * @return a queue in the new context obtained by mapping the current one
   *         using `f`
   */
  def mapK[G[_]](f: F ~> G): Queue[G, A] =
    new Queue[G, A] {
      def offer(a: A): G[Unit] = f(self.offer(a))
      def tryOffer(a: A): G[Boolean] = f(self.tryOffer(a))
      val take: G[A] = f(self.take)
      val tryTake: G[Option[A]] = f(self.tryTake)
    }
}

object Queue {

  /**
   * Constructs an empty, bounded queue holding up to `capacity` elements for
   * `F` data types that are [[Concurrent]]. When the queue is full (contains
   * exactly `capacity` elements), every next [[Queue#offer]] will be
   * backpressured (i.e. the [[Queue#offer]] blocks semantically).
   *
   * @param capacity the maximum capacity of the queue
   * @return an empty, bounded queue
   */
  def bounded[F[_], A](capacity: Int)(implicit F: GenConcurrent[F, _]): F[Queue[F, A]] = {
    assertNonNegative(capacity)
    F.ref(State.empty[F, A]).map(new BoundedQueue(capacity, _))
  }

  /**
   * Constructs a queue through which a single element can pass only in the case
   * when there are at least one taking fiber and at least one offering fiber
   * for `F` data types that are [[Concurrent]]. Both [[Queue#offer]] and
   * [[Queue#take]] semantically block until there is a fiber executing the
   * opposite action, at which point both fibers are freed.
   *
   * @return a synchronous queue
   */
  def synchronous[F[_], A](implicit F: GenConcurrent[F, _]): F[Queue[F, A]] =
    bounded(0)

  /**
   * Constructs an empty, unbounded queue for `F` data types that are
   * [[Concurrent]]. [[Queue#offer]] never blocks semantically, as there is
   * always spare capacity in the queue.
   *
   * @return an empty, unbounded queue
   */
  def unbounded[F[_], A](implicit F: GenConcurrent[F, _]): F[Queue[F, A]] =
    bounded(Int.MaxValue)

  /**
   * Constructs an empty, bounded, dropping queue holding up to `capacity`
   * elements for `F` data types that are [[Concurrent]]. When the queue is full
   * (contains exactly `capacity` elements), every next [[Queue#offer]] will be
   * ignored, i.e. no other elements can be enqueued until there is sufficient
   * capacity in the queue, and the offer effect itself will not semantically
   * block.
   *
   * @param capacity the maximum capacity of the queue
   * @return an empty, bounded, dropping queue
   */
  def dropping[F[_], A](capacity: Int)(implicit F: GenConcurrent[F, _]): F[Queue[F, A]] = {
    assertPositive(capacity, "Dropping")
    F.ref(State.empty[F, A]).map(new DroppingQueue(capacity, _))
  }

  /**
   * Constructs an empty, bounded, circular buffer queue holding up to
   * `capacity` elements for `F` data types that are [[Concurrent]]. The queue
   * always keeps at most `capacity` number of elements, with the oldest
   * element in the queue always being dropped in favor of a new elements
   * arriving in the queue, and the offer effect itself will not semantically
   * block.
   *
   * @param capacity the maximum capacity of the queue
   * @return an empty, bounded, sliding queue
   */
  def circularBuffer[F[_], A](capacity: Int)(
      implicit F: GenConcurrent[F, _]): F[Queue[F, A]] = {
    assertPositive(capacity, "CircularBuffer")
    F.ref(State.empty[F, A]).map(new CircularBufferQueue(capacity, _))
  }

  private def assertNonNegative(capacity: Int): Unit =
    require(capacity >= 0, s"Bounded queue capacity must be non-negative, was: $capacity")

  private def assertPositive(capacity: Int, name: String): Unit =
    require(capacity > 0, s"$name queue capacity must be positive, was: $capacity")

  private sealed abstract class AbstractQueue[F[_], A](
      capacity: Int,
      state: Ref[F, State[F, A]]
  )(implicit F: GenConcurrent[F, _])
      extends Queue[F, A] {

    protected def onOfferNoCapacity(
        s: State[F, A],
        a: A,
        offerer: Deferred[F, Unit],
        poll: Poll[F]
    ): (State[F, A], F[Unit])

    protected def onTryOfferNoCapacity(s: State[F, A], a: A): (State[F, A], F[Boolean])

    def offer(a: A): F[Unit] =
      F.deferred[Unit].flatMap { offerer =>
        F.uncancelable { poll =>
          state.modify {
            case State(queue, size, takers, offerers) if takers.nonEmpty =>
              val (taker, rest) = takers.dequeue
              State(queue, size, rest, offerers) -> taker.complete(a).void

            case State(queue, size, takers, offerers) if size < capacity =>
              State(queue.enqueue(a), size + 1, takers, offerers) -> F.unit

            case s =>
              onOfferNoCapacity(s, a, offerer, poll)
          }.flatten
        }
      }

    def tryOffer(a: A): F[Boolean] =
      state
        .modify {
          case State(queue, size, takers, offerers) if takers.nonEmpty =>
            val (taker, rest) = takers.dequeue
            State(queue, size, rest, offerers) -> taker.complete(a).as(true)

          case State(queue, size, takers, offerers) if size < capacity =>
            State(queue.enqueue(a), size + 1, takers, offerers) -> F.pure(true)

          case s =>
            onTryOfferNoCapacity(s, a)
        }
        .flatten
        .uncancelable

    val take: F[A] =
      F.deferred[A].flatMap { taker =>
        F.uncancelable { poll =>
          state.modify {
            case State(queue, size, takers, offerers) if queue.nonEmpty && offerers.isEmpty =>
              val (a, rest) = queue.dequeue
              State(rest, size - 1, takers, offerers) -> F.pure(a)

            case State(queue, size, takers, offerers) if queue.nonEmpty =>
              val (a, rest) = queue.dequeue
              val ((move, release), tail) = offerers.dequeue
              State(rest.enqueue(move), size, takers, tail) -> release.complete(()).as(a)

            case State(queue, size, takers, offerers) if offerers.nonEmpty =>
              val ((a, release), rest) = offerers.dequeue
              State(queue, size, takers, rest) -> release.complete(()).as(a)

            case State(queue, size, takers, offerers) =>
              val cleanup = state.update { s => s.copy(takers = s.takers.filter(_ ne taker)) }
              State(queue, size, takers.enqueue(taker), offerers) ->
                poll(taker.get).onCancel(cleanup)
          }.flatten
        }
      }

    val tryTake: F[Option[A]] =
      state
        .modify {
          case State(queue, size, takers, offerers) if queue.nonEmpty && offerers.isEmpty =>
            val (a, rest) = queue.dequeue
            State(rest, size - 1, takers, offerers) -> F.pure(a.some)

          case State(queue, size, takers, offerers) if queue.nonEmpty =>
            val (a, rest) = queue.dequeue
            val ((move, release), tail) = offerers.dequeue
            State(rest.enqueue(move), size, takers, tail) -> release.complete(()).as(a.some)

          case State(queue, size, takers, offerers) if offerers.nonEmpty =>
            val ((a, release), rest) = offerers.dequeue
            State(queue, size, takers, rest) -> release.complete(()).as(a.some)

          case s =>
            s -> F.pure(none[A])
        }
        .flatten
        .uncancelable
  }

  private final class BoundedQueue[F[_], A](capacity: Int, state: Ref[F, State[F, A]])(
      implicit F: GenConcurrent[F, _]
  ) extends AbstractQueue(capacity, state) {

    protected def onOfferNoCapacity(
        s: State[F, A],
        a: A,
        offerer: Deferred[F, Unit],
        poll: Poll[F]
    ): (State[F, A], F[Unit]) = {
      val State(queue, size, takers, offerers) = s
      val cleanup = state.update { s => s.copy(offerers = s.offerers.filter(_._2 ne offerer)) }
      State(queue, size, takers, offerers.enqueue(a -> offerer)) -> poll(offerer.get)
        .onCancel(cleanup)
    }

    protected def onTryOfferNoCapacity(s: State[F, A], a: A): (State[F, A], F[Boolean]) =
      s -> F.pure(false)
  }

  private final class DroppingQueue[F[_], A](capacity: Int, state: Ref[F, State[F, A]])(
      implicit F: GenConcurrent[F, _]
  ) extends AbstractQueue(capacity, state) {

    protected def onOfferNoCapacity(
        s: State[F, A],
        a: A,
        offerer: Deferred[F, Unit],
        poll: Poll[F]
    ): (State[F, A], F[Unit]) =
      s -> F.unit

    protected def onTryOfferNoCapacity(s: State[F, A], a: A): (State[F, A], F[Boolean]) =
      s -> F.pure(false)
  }

  private final class CircularBufferQueue[F[_], A](capacity: Int, state: Ref[F, State[F, A]])(
      implicit F: GenConcurrent[F, _]
  ) extends AbstractQueue(capacity, state) {

    protected def onOfferNoCapacity(
        s: State[F, A],
        a: A,
        offerer: Deferred[F, Unit],
        poll: Poll[F]
    ): (State[F, A], F[Unit]) = {
      // dotty doesn't like cats map on tuples
      val (ns, fb) = onTryOfferNoCapacity(s, a)
      (ns, fb.void)
    }

    protected def onTryOfferNoCapacity(s: State[F, A], a: A): (State[F, A], F[Boolean]) = {
      val State(queue, size, takers, offerers) = s
      val (_, rest) = queue.dequeue
      State(rest.enqueue(a), size, takers, offerers) -> F.pure(true)
    }
  }

  private final case class State[F[_], A](
      queue: ScalaQueue[A],
      size: Int,
      takers: ScalaQueue[Deferred[F, A]],
      offerers: ScalaQueue[(A, Deferred[F, Unit])]
  )

  private object State {
    def empty[F[_], A]: State[F, A] =
      State(ScalaQueue.empty, 0, ScalaQueue.empty, ScalaQueue.empty)
  }
}
