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

import cats.{~>, Order}
import cats.implicits._
import cats.effect.kernel.{Concurrent, Deferred, Poll, Ref}
import scala.annotation.tailrec

import scala.collection.immutable.{Queue => ScalaQueue}

/**
 * A purely functional Priority Queue implementation based
 * on a binomial heap (Okasaki)
 */

abstract class PQueue[F[_], A] { self =>

  /**
   * Enqueues the given element, possibly semantically
   * blocking until sufficient capacity becomes available.
   *
   * @param a the element to be put at the back of the queue
   */
  def offer(a: A): F[Unit]

  /**
   * Attempts to enqueue the given element without
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
  def mapK[G[_]](f: F ~> G): PQueue[G, A] =
    new PQueue[G, A] {
      def offer(a: A): G[Unit] = f(self.offer(a))
      def tryOffer(a: A): G[Boolean] = f(self.tryOffer(a))
      val take: G[A] = f(self.take)
      val tryTake: G[Option[A]] = f(self.tryTake)
    }

}

object PQueue {

  def bounded[F[_], A](capacity: Int)(implicit F: Concurrent[F], O: Order[A]): F[PQueue[F, A]] =
    F.ref(State.empty[F, A]).map { ref =>
      new PQueueImpl[F, A](ref, capacity) {
        implicit val Ord = O
      }
    }

  def unbounded[F[_], A](implicit F: Concurrent[F], O: Order[A]): F[PQueue[F, A]] =
    bounded(Int.MaxValue)

  private[std] abstract class PQueueImpl[F[_], A](ref: Ref[F, State[F, A]], capacity: Int)
      extends PQueue[F, A] {
    implicit val Ord: Order[A]

    //TODO semantic blocking
    def offer(a: A): F[Unit] = ???

    def tryOffer(a: A): F[Boolean] =
      ref.modify(s => {
        if (s.size == capacity) {
          s -> false
        } else {
          val newHeap = s.heap.insert(a)
          s.copy(size = s.size + 1, heap = newHeap) -> true
        }
      })

    //TODO semantic blocking
    def take: F[A] = ???

    def tryTake: F[Option[A]] =
      ref.modify(s => {
        if (s.size == 0) {
          s -> None
        } else {
          val (newHeap, x) = s.heap.take
          s.copy(size = s.size - 1, heap = newHeap) -> x
        }
      })
  }

  private[std] case class State[F[_], A](
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

  private[std] abstract case class BinomialHeap[A](trees: List[Tree[A]]) { self =>

    //Allows us to fix this on construction, ensuring some safety from
    //different Ord instances for A
    implicit val Ord: Order[A]

    def insert(tree: Tree[A]): BinomialHeap[A] =
      BinomialHeap[A](BinomialHeap.insert(tree, trees))

    def insert(a: A): BinomialHeap[A] = insert(Tree(0, a, Nil))

    def peek: Option[A] = BinomialHeap.peek(trees)

    def take: (BinomialHeap[A], Option[A]) = {
      val (ts, head) = BinomialHeap.take(trees)
      BinomialHeap(ts) -> head
    }
  }

  private[std] object BinomialHeap {

    def empty[A: Order]: BinomialHeap[A] = BinomialHeap(Nil)

    def apply[A](trees: List[Tree[A]])(implicit ord: Order[A]) =
      new BinomialHeap[A](trees) {
        implicit val Ord = ord
      }

    /**
     * Assumes trees is monotonically increasing in rank
     */
    @tailrec
    def insert[A: Order](tree: Tree[A], trees: List[Tree[A]]): List[Tree[A]] =
      trees match {
        case Nil => List(tree)
        case l @ (t :: ts) =>
          if (tree.rank < t.rank)
            (tree :: l)
          else insert(tree.link(t), ts)
      }

    /**
     * Assumes each list is monotonically increasing in rank
     */
    def merge[A: Order](lhs: List[Tree[A]], rhs: List[Tree[A]]): List[Tree[A]] =
      (lhs, rhs) match {
        case (Nil, ts) => ts
        case (ts, Nil) => ts
        case (l1 @ (t1 :: ts1), l2 @ (t2 :: ts2)) =>
          if (t1.rank < t2.rank) t1 :: merge(ts1, l2)
          else if (t2.rank < t1.rank) t2 :: merge(l1, ts2)
          else insert(t1.link(t2), merge(ts1, ts2))
      }

    //TODO we can make this O(1) by storing a pointer to the smallest root instead
    def peek[A](trees: List[Tree[A]])(implicit Ord: Order[A]): Option[A] =
      trees match {
        case Nil => None
        case h :: t =>
          peek(t) match {
            case None => Some(h.value)
            case Some(v) => Some(Ord.min(h.value, v))
          }
      }

    //TODO we can make this O(1) by storing a pointer to the smallest root instead
    def take[A](trees: List[Tree[A]])(implicit Ord: Order[A]): (List[Tree[A]], Option[A]) = {
      //Note this is partial but we don't want to allocate a NonEmptyList
      def min(trees: List[Tree[A]]): (Tree[A], List[Tree[A]]) =
        trees match {
          case t :: Nil => (t, Nil)
          case t :: ts => {
            val (t1, ts1) = min(ts)
            if (Ord.lteqv(t.value, t1.value)) (t, ts) else (t1, t :: ts1)
          }
          case _ => throw new AssertionError
        }

      trees match {
        case Nil => Nil -> None
        case l => {
          val (t, ts) = min(l)
          merge(t.children.reverse, ts) -> Some(t.value)
        }
      }

    }

  }

  /**
   * Children are stored in monotonically decreasing order of rank
   */
  private[std] case class Tree[A](rank: Int, value: A, children: List[Tree[A]]) {

    /**
     * Link two trees of rank r to produce a tree of rank r + 1
     */
    def link(other: Tree[A])(implicit Ord: Order[A]): Tree[A] = {
      assert(rank == other.rank)
      if (Ord.lteqv(value, other.value))
        Tree(rank + 1, value, other :: children)
      else Tree(rank + 1, other.value, this :: other.children)
    }

  }

}
