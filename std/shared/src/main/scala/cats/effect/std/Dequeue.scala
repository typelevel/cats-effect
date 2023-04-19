/*
 * Copyright 2020-2023 Typelevel
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

import cats.{~>, Contravariant, Functor, Invariant, Monad}
import cats.effect.kernel.{Deferred, GenConcurrent, Ref}
import cats.effect.kernel.syntax.all._
import cats.effect.std.internal.BankersQueue
import cats.syntax.all._

import scala.collection.immutable.{Queue => ScalaQueue}

trait Dequeue[F[_], A] extends Queue[F, A] with DequeueSource[F, A] with DequeueSink[F, A] {
  self =>

  /**
   * Reverse the dequeue in constant time
   */
  def reverse: F[Unit]

  /**
   * Modifies the context in which this dequeue is executed using the natural transformation
   * `f`.
   *
   * @return
   *   a queue in the new context obtained by mapping the current one using `f`
   */
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
      def size: G[Int] = f(self.size)
    }

}

object Dequeue {

  /**
   * Constructs an empty, bounded dequeue holding up to `capacity` elements for `F` data types
   * that are [[cats.effect.kernel.GenConcurrent]]. When the queue is full (contains exactly
   * `capacity` elements), every next [[Queue#offer]] will be backpressured (i.e. the
   * [[Queue#offer]] blocks semantically).
   *
   * @param capacity
   *   the maximum capacity of the queue
   * @return
   *   an empty, bounded queue
   */
  def bounded[F[_], A](capacity: Int)(implicit F: GenConcurrent[F, _]): F[Dequeue[F, A]] = {
    assertNonNegative(capacity)
    F.ref(State.empty[F, A]).map(new BoundedDequeue(capacity, _))
  }

  /**
   * Constructs an empty, unbounded dequeue for `F` data types that are
   * [[cats.effect.kernel.GenConcurrent]]. [[Queue#offer]] never blocks semantically, as there
   * is always spare capacity in the queue.
   *
   * @return
   *   an empty, unbounded queue
   */
  def unbounded[F[_], A](implicit F: GenConcurrent[F, _]): F[Dequeue[F, A]] =
    bounded(Int.MaxValue)

  implicit def catsInvariantForDequeue[F[_]: Functor]: Invariant[Dequeue[F, *]] =
    new Invariant[Dequeue[F, *]] {
      override def imap[A, B](fa: Dequeue[F, A])(f: A => B)(g: B => A): Dequeue[F, B] =
        new Dequeue[F, B] {
          override def takeBack: F[B] =
            fa.takeBack.map(f)

          override def tryTakeBack: F[Option[B]] =
            fa.tryTakeBack.map(_.map(f))

          override def takeFront: F[B] =
            fa.takeFront.map(f)

          override def tryTakeFront: F[Option[B]] =
            fa.tryTakeFront.map(_.map(f))

          override def offerBack(b: B): F[Unit] =
            fa.offerBack(g(b))

          override def tryOfferBack(b: B): F[Boolean] =
            fa.tryOfferBack(g(b))

          override def offerFront(b: B): F[Unit] =
            fa.offerFront(g(b))

          override def tryOfferFront(b: B): F[Boolean] =
            fa.tryOfferFront(g(b))

          override def reverse: F[Unit] = fa.reverse

          override def size: F[Int] = fa.size
        }
    }

  private[std] class BoundedDequeue[F[_], A](capacity: Int, state: Ref[F, State[F, A]])(
      implicit F: GenConcurrent[F, _])
      extends Dequeue[F, A] {

    override def offerBack(a: A): F[Unit] =
      _offer(a, _.pushBack(a))

    override def tryOfferBack(a: A): F[Boolean] =
      _tryOffer(_.pushBack(a))

    override def takeBack: F[A] =
      _take(_.tryPopBack)

    override def tryTakeBack: F[Option[A]] =
      _tryTake(_.tryPopBack)

    override def offerFront(a: A): F[Unit] =
      _offer(a, _.pushFront(a))

    override def tryOfferFront(a: A): F[Boolean] =
      _tryOffer(_.pushFront(a))

    override def takeFront: F[A] =
      _take(_.tryPopFront)

    override def tryTakeFront: F[Option[A]] =
      _tryTake(_.tryPopFront)

    override def reverse: F[Unit] =
      state.update {
        case State(queue, size, takers, offerers) =>
          State(queue.reverse, size, takers, offerers)
      }

    private def _offer(a: A, update: BankersQueue[A] => BankersQueue[A]): F[Unit] =
      F.uncancelable { poll =>
        F.deferred[Unit].flatMap { offerer =>
          state.modify {
            case State(queue, size, takers, offerers) if takers.nonEmpty =>
              val (taker, rest) = takers.dequeue
              State(update(queue), size, rest, offerers) -> taker.complete(()).void

            case State(queue, size, takers, offerers) if size < capacity =>
              State(update(queue), size + 1, takers, offerers) -> F.unit

            case s =>
              val State(queue, size, takers, offerers) = s

              val cleanup = state modify { s =>
                val offerers2 = s.offerers.filter(_ ne offerer)

                if (offerers2.isEmpty) {
                  s.copy(offerers = offerers2) -> F.unit
                } else {
                  val (release, rest) = offerers2.dequeue
                  s.copy(offerers = rest) -> release.complete(()).void
                }
              }

              State(queue, size, takers, offerers.enqueue(offerer)) ->
                (poll(offerer.get) *> poll(_offer(a, update))).onCancel(cleanup.flatten)
          }.flatten
        }
      }

    private def _tryOffer(update: BankersQueue[A] => BankersQueue[A]) =
      state.flatModify {
        case State(queue, size, takers, offerers) if takers.nonEmpty =>
          val (taker, rest) = takers.dequeue
          State(update(queue), size, rest, offerers) -> taker.complete(()).as(true)

        case State(queue, size, takers, offerers) if size < capacity =>
          State(update(queue), size + 1, takers, offerers) -> F.pure(true)

        case s =>
          s -> F.pure(false)
      }

    private def _take(dequeue: BankersQueue[A] => (BankersQueue[A], Option[A])): F[A] =
      F uncancelable { poll =>
        F.deferred[Unit] flatMap { taker =>
          val modificationF = state modify {
            case State(queue, size, takers, offerers) if queue.nonEmpty && offerers.isEmpty =>
              val (rest, ma) = dequeue(queue)
              val a = ma.get
              State(rest, size - 1, takers, offerers) -> F.pure(a)

            case State(queue, size, takers, offerers) if queue.nonEmpty =>
              val (rest, ma) = dequeue(queue)
              val a = ma.get
              val (release, tail) = offerers.dequeue
              State(rest, size - 1, takers, tail) -> release.complete(()).as(a)

            case State(queue, size, takers, offerers) =>
              val cleanup = state modify { s =>
                val takers2 = s.takers.filter(_ ne taker)
                if (takers2.isEmpty) {
                  s.copy(takers = takers2) -> F.unit
                } else {
                  val (taker, rest) = takers2.dequeue
                  s.copy(takers = rest) -> taker.complete(()).void
                }
              }

              val await = poll(taker.get).onCancel(cleanup.flatten) *> poll(_take(dequeue))
                .onCancel(notifyNextTaker.flatten)

              val (fulfill, offerers2) = if (offerers.isEmpty) {
                (await, offerers)
              } else {
                val (release, rest) = offerers.dequeue
                (release.complete(()) *> await, rest)
              }

              State(queue, size, takers.enqueue(taker), offerers2) -> fulfill
          }

          modificationF.flatten
        }
      }

    private[this] val notifyNextTaker =
      state modify { s =>
        if (s.takers.isEmpty) {
          s -> F.unit
        } else {
          val (taker, rest) = s.takers.dequeue
          s.copy(takers = rest) -> taker.complete(()).void
        }
      }

    private def _tryTake(
        dequeue: BankersQueue[A] => (BankersQueue[A], Option[A])): F[Option[A]] =
      state.flatModify {
        case State(queue, size, takers, offerers) if queue.nonEmpty && offerers.isEmpty =>
          val (rest, ma) = dequeue(queue)
          State(rest, size - 1, takers, offerers) -> F.pure(ma)

        case State(queue, size, takers, offerers) if queue.nonEmpty =>
          val (rest, ma) = dequeue(queue)
          val (release, tail) = offerers.dequeue
          State(rest, size - 1, takers, tail) -> release.complete(()).as(ma)

        case s =>
          s -> F.pure(none[A])
      }

    override def size: F[Int] = state.get.map(_.size)
  }

  private def assertNonNegative(capacity: Int): Unit =
    require(capacity >= 0, s"Bounded queue capacity must be non-negative, was: $capacity")

  private[std] final case class State[F[_], A](
      queue: BankersQueue[A],
      size: Int,
      takers: ScalaQueue[Deferred[F, Unit]],
      offerers: ScalaQueue[Deferred[F, Unit]])

  private[std] object State {
    def empty[F[_], A]: State[F, A] =
      State(BankersQueue.empty, 0, ScalaQueue.empty, ScalaQueue.empty)
  }

}

trait DequeueSource[F[_], A] extends QueueSource[F, A] {

  /**
   * Dequeues an element from the back of the dequeue, possibly fiber blocking until an element
   * becomes available.
   */
  def takeBack: F[A]

  /**
   * Attempts to dequeue an element from the back of the dequeue, if one is available without
   * fiber blocking.
   *
   * @return
   *   an effect that describes whether the dequeueing of an element from the dequeue succeeded
   *   without blocking, with `None` denoting that no element was available
   */
  def tryTakeBack: F[Option[A]]

  /**
   * Attempts to dequeue elements from the back of the dequeue, if they available without
   * semantically blocking. This is a convenience method that recursively runs `tryTakeBack`. It
   * does not provide any additional performance benefits.
   *
   * @param maxN
   *   The max elements to dequeue. Passing `None` will try to dequeue the whole queue.
   *
   * @return
   *   an effect that contains the dequeued elements
   */
  def tryTakeBackN(maxN: Option[Int])(implicit F: Monad[F]): F[List[A]] =
    _tryTakeN(tryTakeBack)(maxN)

  /**
   * Dequeues an element from the front of the dequeue, possibly semantically blocking until an
   * element becomes available.
   */
  def takeFront: F[A]

  /**
   * Attempts to dequeue an element from the front of the dequeue, if one is available without
   * fiber blocking.
   *
   * @return
   *   an effect that describes whether the dequeueing of an element from the dequeue succeeded
   *   without blocking, with `None` denoting that no element was available
   */
  def tryTakeFront: F[Option[A]]

  /**
   * Attempts to dequeue elements from the front of the dequeue, if they available without
   * semantically blocking. This is a convenience method that recursively runs `tryTakeFront`.
   * It does not provide any additional performance benefits.
   *
   * @param maxN
   *   The max elements to dequeue. Passing `None` will try to dequeue the whole queue.
   *
   * @return
   *   an effect that contains the dequeued elements
   */
  def tryTakeFrontN(maxN: Option[Int])(implicit F: Monad[F]): F[List[A]] =
    _tryTakeN(tryTakeFront)(maxN)

  /**
   * Alias for takeFront in order to implement Queue
   */
  override def take: F[A] = takeFront

  /**
   * Alias for tryTakeFront in order to implement Queue
   */
  override def tryTake: F[Option[A]] = tryTakeFront

  private def _tryTakeN(_tryTake: F[Option[A]])(maxN: Option[Int])(
      implicit F: Monad[F]): F[List[A]] = {

    DequeueSource.assertMaxNPositive(maxN)

    def loop(i: Int, limit: Int, acc: List[A]): F[List[A]] =
      if (i >= limit)
        F.pure(acc.reverse)
      else
        _tryTake flatMap {
          case Some(a) => loop(i + 1, limit, a :: acc)
          case None => F.pure(acc.reverse)
        }

    maxN match {
      case Some(limit) => loop(0, limit, Nil)
      case None => loop(0, Int.MaxValue, Nil)
    }
  }

}

object DequeueSource {
  private def assertMaxNPositive(maxN: Option[Int]): Unit = maxN match {
    case Some(n) if n <= 0 =>
      throw new IllegalArgumentException(s"Provided maxN parameter must be positive, was $n")
    case _ => ()
  }

  implicit def catsFunctorForDequeueSource[F[_]: Functor]: Functor[DequeueSource[F, *]] =
    new Functor[DequeueSource[F, *]] {
      override def map[A, B](fa: DequeueSource[F, A])(f: A => B): DequeueSource[F, B] =
        new DequeueSource[F, B] {
          override def takeBack: F[B] =
            fa.takeBack.map(f)

          override def tryTakeBack: F[Option[B]] =
            fa.tryTakeBack.map(_.map(f))

          override def takeFront: F[B] =
            fa.takeFront.map(f)

          override def tryTakeFront: F[Option[B]] =
            fa.tryTakeFront.map(_.map(f))

          override def size: F[Int] =
            fa.size
        }
    }
}

trait DequeueSink[F[_], A] extends QueueSink[F, A] {

  /**
   * Enqueues the given element at the back of the dequeue, possibly fiber blocking until
   * sufficient capacity becomes available.
   *
   * @param a
   *   the element to be put at the back of the dequeue
   */
  def offerBack(a: A): F[Unit]

  /**
   * Attempts to enqueue the given element at the back of the dequeue without semantically
   * blocking.
   *
   * @param a
   *   the element to be put at the back of the dequeue
   * @return
   *   an effect that describes whether the enqueuing of the given element succeeded without
   *   blocking
   */
  def tryOfferBack(a: A): F[Boolean]

  /**
   * Attempts to enqueue the given elements at the back of the queue without semantically
   * blocking. If an item in the list cannot be enqueued, the remaining elements will be
   * returned. This is a convenience method that recursively runs `tryOffer` and does not offer
   * any additional performance benefits.
   *
   * @param list
   *   the elements to be put at the back of the queue
   * @return
   *   an effect that contains the remaining valus that could not be offered.
   */
  def tryOfferBackN(list: List[A])(implicit F: Monad[F]): F[List[A]] =
    _tryOfferN(list)(tryOfferBack)

  /**
   * Enqueues the given element at the front of the dequeue, possibly semantically blocking
   * until sufficient capacity becomes available.
   *
   * @param a
   *   the element to be put at the back of the dequeue
   */
  def offerFront(a: A): F[Unit]

  /**
   * Attempts to enqueue the given element at the front of the dequeue without semantically
   * blocking.
   *
   * @param a
   *   the element to be put at the back of the dequeue
   * @return
   *   an effect that describes whether the enqueuing of the given element succeeded without
   *   blocking
   */
  def tryOfferFront(a: A): F[Boolean]

  /**
   * Attempts to enqueue the given elements at the front of the queue without semantically
   * blocking. If an item in the list cannot be enqueued, the remaining elements will be
   * returned. This is a convenience method that recursively runs `tryOffer` and does not offer
   * any additional performance benefits.
   *
   * @param list
   *   the elements to be put at the front of the queue
   * @return
   *   an effect that contains the remaining valus that could not be offered.
   */
  def tryOfferFrontN(list: List[A])(implicit F: Monad[F]): F[List[A]] =
    _tryOfferN(list)(tryOfferFront)

  /**
   * Alias for offerBack in order to implement Queue
   */
  override def offer(a: A): F[Unit] = offerBack(a)

  /**
   * Alias for tryOfferBack in order to implement Queue
   */
  override def tryOffer(a: A): F[Boolean] = tryOfferBack(a)

  private def _tryOfferN(list: List[A])(_tryOffer: A => F[Boolean])(
      implicit F: Monad[F]): F[List[A]] = list match {
    case Nil => F.pure(list)
    case h :: t =>
      _tryOffer(h).ifM(
        tryOfferN(t),
        F.pure(list)
      )
  }

}

object DequeueSink {
  implicit def catsContravariantForDequeueSink[F[_]]: Contravariant[DequeueSink[F, *]] =
    new Contravariant[DequeueSink[F, *]] {
      override def contramap[A, B](fa: DequeueSink[F, A])(f: B => A): DequeueSink[F, B] =
        new DequeueSink[F, B] {
          override def offerBack(b: B): F[Unit] =
            fa.offerBack(f(b))

          override def tryOfferBack(b: B): F[Boolean] =
            fa.tryOfferBack(f(b))

          override def offerFront(b: B): F[Unit] =
            fa.offerFront(f(b))

          override def tryOfferFront(b: B): F[Boolean] =
            fa.tryOfferFront(f(b))
        }
    }

}
