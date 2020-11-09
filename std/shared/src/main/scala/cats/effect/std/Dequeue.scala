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
import cats.effect.kernel.{Deferred, GenConcurrent, Ref}
import cats.effect.kernel.syntax.all._
import cats.effect.std.internal.BankersQueue
import cats.syntax.all._

import scala.collection.immutable.{Queue => ScalaQueue}

trait Dequeue[F[_], A] extends Queue[F, A] { self =>

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

  override def mapK[G[_]](f: F ~> G): Dequeue[G, A] =
    new Dequeue[G, A] {
      def offerBack(a: A): G[Unit] = f(self.offerBack(a))
      def tryOfferBack(a: A): G[Boolean] = f(self.tryOfferBack(a))
      def takeBack: G[A] = f(self.takeBack)
      def tryTakeBack: G[Option[A]] = f(self.tryTakeBack)
      def offerFront(a: A): G[Unit] = f(self.offerFront(a))
      def tryOfferFront(a: A): G[Boolean] = f(self.tryOfferFront(a))
      def takeFront: G[A] = f(self.takeFront)
      def tryTakeFront: G[Option[A]] = f(self.tryTakeFront)
      def reverse: G[Unit] = f(self.reverse)
    }

}

object Dequeue {

  /**
   * Constructs an empty, bounded dequeue holding up to `capacity` elements for
   * `F` data types that are [[Concurrent]]. When the queue is full (contains
   * exactly `capacity` elements), every next [[Queue#offer]] will be
   * backpressured (i.e. the [[Queue#offer]] blocks semantically).
   *
   * @param capacity the maximum capacity of the queue
   * @return an empty, bounded queue
   */
  def bounded[F[_], A](capacity: Int)(implicit F: GenConcurrent[F, _]): F[Dequeue[F, A]] = {
    assertNonNegative(capacity)
    F.ref(State.empty[F, A]).map(new BoundedDequeue(capacity, _))
  }

  /**
   * Constructs an empty, unbounded dequeue for `F` data types that are
   * [[Concurrent]]. [[Queue#offer]] never blocks semantically, as there is
   * always spare capacity in the queue.
   *
   * @return an empty, unbounded queue
   */
  def unbounded[F[_], A](implicit F: GenConcurrent[F, _]): F[Dequeue[F, A]] =
    bounded(Int.MaxValue)

  private[std] class BoundedDequeue[F[_], A](capacity: Int, state: Ref[F, State[F, A]])(
      implicit F: GenConcurrent[F, _])
      extends Dequeue[F, A] {

    override def offerBack(a: A): F[Unit] =
      _offer(a, queue => queue.pushBack(a))

    override def tryOfferBack(a: A): F[Boolean] =
      _tryOffer(a, queue => queue.pushBack(a))

    override def takeBack: F[A] =
      _take(queue => queue.tryPopBack)

    override def tryTakeBack: F[Option[A]] =
      _tryTake(queue => queue.tryPopBack)

    override def offerFront(a: A): F[Unit] =
      _offer(a, queue => queue.pushFront(a))

    override def tryOfferFront(a: A): F[Boolean] =
      _tryOffer(a, queue => queue.pushFront(a))

    override def takeFront: F[A] =
      _take(queue => queue.tryPopFront)

    override def tryTakeFront: F[Option[A]] =
      _tryTake(queue => queue.tryPopFront)

    override def reverse: F[Unit] =
      state.update {
        case State(queue, size, takers, offerers) =>
          State(queue.reverse, size, takers, offerers)
      }

    private def _offer(a: A, update: BankersQueue[A] => BankersQueue[A]): F[Unit] =
      F.deferred[Unit].flatMap { offerer =>
        F.uncancelable { poll =>
          state.modify {
            case State(queue, size, takers, offerers) if takers.nonEmpty =>
              val (taker, rest) = takers.dequeue
              State(queue, size, rest, offerers) -> taker.complete(a).void

            case State(queue, size, takers, offerers) if size < capacity =>
              State(update(queue), size + 1, takers, offerers) -> F.unit

            case s =>
              val State(queue, size, takers, offerers) = s
              val cleanup = state.update { s =>
                s.copy(offerers = s.offerers.filter(_._2 ne offerer))
              }
              State(queue, size, takers, offerers.enqueue(a -> offerer)) -> poll(offerer.get)
                .onCancel(cleanup)
          }.flatten
        }
      }

    private def _tryOffer(a: A, update: BankersQueue[A] => BankersQueue[A]) =
      state
        .modify {
          case State(queue, size, takers, offerers) if takers.nonEmpty =>
            val (taker, rest) = takers.dequeue
            State(queue, size, rest, offerers) -> taker.complete(a).as(true)

          case State(queue, size, takers, offerers) if size < capacity =>
            State(update(queue), size + 1, takers, offerers) -> F.pure(true)

          case s =>
            s -> F.pure(false)
        }
        .flatten
        .uncancelable

    private def _take(dequeue: BankersQueue[A] => (BankersQueue[A], Option[A])): F[A] =
      F.deferred[A].flatMap { taker =>
        F.uncancelable { poll =>
          state.modify {
            case State(queue, size, takers, offerers) if queue.nonEmpty && offerers.isEmpty =>
              val (rest, ma) = dequeue(queue)
              val a = ma.get
              State(rest, size - 1, takers, offerers) -> F.pure(a)

            case State(queue, size, takers, offerers) if queue.nonEmpty =>
              val (rest, ma) = dequeue(queue)
              val a = ma.get
              val ((move, release), tail) = offerers.dequeue
              State(rest.pushBack(move), size, takers, tail) -> release.complete(()).as(a)

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

    private def _tryTake(
        dequeue: BankersQueue[A] => (BankersQueue[A], Option[A])): F[Option[A]] =
      state
        .modify {
          case State(queue, size, takers, offerers) if queue.nonEmpty && offerers.isEmpty =>
            val (rest, ma) = dequeue(queue)
            State(rest, size - 1, takers, offerers) -> F.pure(ma)

          case State(queue, size, takers, offerers) if queue.nonEmpty =>
            val (rest, ma) = dequeue(queue)
            val ((move, release), tail) = offerers.dequeue
            State(rest.pushBack(move), size, takers, tail) -> release.complete(()).as(ma)

          case State(queue, size, takers, offerers) if offerers.nonEmpty =>
            val ((a, release), rest) = offerers.dequeue
            State(queue, size, takers, rest) -> release.complete(()).as(a.some)

          case s =>
            s -> F.pure(none[A])
        }
        .flatten
        .uncancelable
  }

  private def assertNonNegative(capacity: Int): Unit =
    require(capacity >= 0, s"Bounded queue capacity must be non-negative, was: $capacity")

  private[std] final case class State[F[_], A](
      queue: BankersQueue[A],
      size: Int,
      takers: ScalaQueue[Deferred[F, A]],
      offerers: ScalaQueue[(A, Deferred[F, Unit])]
  )

  private[std] object State {
    def empty[F[_], A]: State[F, A] =
      State(BankersQueue.empty, 0, ScalaQueue.empty, ScalaQueue.empty)
  }

}
