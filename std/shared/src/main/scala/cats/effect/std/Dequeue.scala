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

//TODO this should extend queue
trait Dequeue[F[_], A] extends Queue[F, A] { self =>

  def offerBack(a: A): F[Unit]

  def takeBack: F[A]

  def tryTakeBack: F[Option[A]]

  override def mapK[G[_]](f: F ~> G): Dequeue[G, A] =
    new Dequeue[G, A] {
      def offer(a: A): G[Unit] = f(self.offer(a))
      def tryOffer(a: A): G[Boolean] = f(self.tryOffer(a))
      def take: G[A] = f(self.take)
      def tryTake: G[Option[A]] = f(self.tryTake)
      def offerBack(a: A): G[Unit] = f(self.offerBack(a))
      def takeBack: G[A] = f(self.takeBack)
      def tryTakeBack: G[Option[A]] = f(self.tryTakeBack)
    }

}

object Dequeue {

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
    F.ref(State.empty[F, A]).map(new BoundedDequeue(capacity, _))
  }

  /**
   * Constructs an empty, unbounded queue for `F` data types that are
   * [[Concurrent]]. [[Queue#offer]] never blocks semantically, as there is
   * always spare capacity in the queue.
   *
   * @return an empty, unbounded queue
   */
  def unbounded[F[_], A](implicit F: GenConcurrent[F, _]): F[Queue[F, A]] =
    bounded(Int.MaxValue)

  private[std] class BoundedDequeue[F[_], A](capacity: Int, state: Ref[F, State[F, A]])
      extends Dequeue[F, A] {

    override def offer(a: A): F[Unit] = ???

    override def tryOffer(a: A): F[Boolean] = ???

    override def take: F[A] = ???

    override def tryTake: F[Option[A]] = ???

    override def offerBack(a: A): F[Unit] = ???

    override def takeBack: F[A] = ???

    override def tryTakeBack: F[Option[A]] = ???

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

  /**
   * A banker's dequeue a la Okasaki
   *
   * Maintains the invariants that:
   * - frontLen <= rebalanceConstant * backLen + 1
   * - backLen <= rebalanceConstant * frontLen + 1
   */
  private[std] final case class BankersQueue[A](
      front: List[A],
      frontLen: Int,
      back: List[A],
      backLen: Int) {
    import BankersQueue._
    def rebalance(): BankersQueue[A] =
      if (frontLen > rebalanceConstant * backLen + 1) {
        val i = (frontLen + backLen) / 2
        val j = frontLen + backLen - i
        val f = front.take(i)
        val b = back ++ f.drop(i).reverse
        BankersQueue(f, i, b, j)
      } else if (backLen > rebalanceConstant * frontLen + 1) {
        val i = (frontLen + backLen) / 2
        val j = frontLen + backLen - i
        val f = front ++ back.drop(j).reverse
        val b = back.take(j)
        BankersQueue(f, i, b, j)
      } else this

    def pushFront(a: A): BankersQueue[A] =
      BankersQueue(a :: front, frontLen + 1, back, backLen).rebalance()

    def pushBack(a: A): BankersQueue[A] =
      BankersQueue(front, frontLen, a :: back, backLen + 1).rebalance()

    def tryPopFront: (BankersQueue[A], Option[A]) =
      if (frontLen > 0)
        BankersQueue(front.tail, frontLen - 1, back, backLen).rebalance() -> Some(front.head)
      else if (backLen > 0) BankersQueue(front, frontLen, Nil, 0) -> Some(back.head)
      else this -> None

    def tryPopBack: (BankersQueue[A], Option[A]) =
      if (backLen > 0)
        BankersQueue(front, frontLen, back.tail, backLen - 1).rebalance() -> Some(back.head)
      else if (frontLen > 0) BankersQueue(Nil, 0, back, backLen) -> Some(front.head)
      else this -> None
  }

  object BankersQueue {
    val rebalanceConstant = 2

    def empty[A]: BankersQueue[A] = BankersQueue(Nil, 0, Nil, 0)
  }
}
