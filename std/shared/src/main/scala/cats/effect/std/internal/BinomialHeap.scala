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

package cats.effect.std.internal

import cats.Order

import scala.annotation.tailrec

/**
 * A binomial heap is a list of trees maintaining the following invariants:
 *   - The list is strictly monotonically increasing in the rank of the trees, where the rank of
 *     a tree is defined as the height of the tree ie the number of nodes on the longest path
 *     from the root to a leaf In fact, a binomial heap built from n elements has is a tree of
 *     rank i iff there is a 1 in the ith digit of the binary representation of n Consequently,
 *     the length of the list is <= 1 + log(n)
 *   - Each tree satisfies the heap property (the value at any node is greater than that of its
 *     parent). This means that the smallest element of the heap is found at one of the roots of
 *     the trees
 *   - The ranks of the children of a node are strictly monotonically decreasing (in fact the
 *     rank of the ith child is r - i)
 */
private[std] abstract case class BinomialHeap[A](trees: List[BinomialTree[A]]) { self =>

  // Allows us to fix this on construction, ensuring some safety from
  // different Ord instances for A
  implicit val Ord: Order[A]

  def nonEmpty: Boolean = trees.nonEmpty

  def insert(tree: BinomialTree[A]): BinomialHeap[A] =
    BinomialHeap[A](BinomialHeap.insert(tree, trees))

  def insert(a: A): BinomialHeap[A] = insert(BinomialTree(0, a, Nil))

  /**
   * Assumes heap is non-empty. Used in Dequeue where we track size externally
   */
  def take: (BinomialHeap[A], A) = {
    val (ts, head) = BinomialHeap.take(trees)
    BinomialHeap(ts) -> head.get
  }

  def tryTake: (BinomialHeap[A], Option[A]) = {
    val (ts, head) = BinomialHeap.take(trees)
    BinomialHeap(ts) -> head
  }
}

private[std] object BinomialHeap {

  def empty[A: Order]: BinomialHeap[A] = BinomialHeap(Nil)

  def apply[A](trees: List[BinomialTree[A]])(implicit ord: Order[A]) =
    new BinomialHeap[A](trees) {
      implicit val Ord: Order[A] = ord
    }

  /**
   * Assumes trees is strictly monotonically increasing in rank
   */
  @tailrec
  def insert[A: Order](
      tree: BinomialTree[A],
      trees: List[BinomialTree[A]]): List[BinomialTree[A]] =
    trees match {
      case Nil => tree :: Nil
      case l @ (t :: ts) =>
        if (tree.rank < t.rank)
          tree :: l
        else insert(tree.link(t), ts)
    }

  /**
   * Assumes each list is strictly monotonically increasing in rank
   */
  def merge[A: Order](
      lhs: List[BinomialTree[A]],
      rhs: List[BinomialTree[A]]): List[BinomialTree[A]] =
    (lhs, rhs) match {
      case (Nil, ts) => ts
      case (ts, Nil) => ts
      case (l1 @ (t1 :: ts1), l2 @ (t2 :: ts2)) =>
        if (t1.rank < t2.rank) t1 :: merge(ts1, l2)
        else if (t2.rank < t1.rank) t2 :: merge(l1, ts2)
        else insert(t1.link(t2), merge(ts1, ts2))
    }

  def take[A](trees: List[BinomialTree[A]])(
      implicit Ord: Order[A]): (List[BinomialTree[A]], Option[A]) = {
    // Note this is partial but we don't want to allocate a NonEmptyList
    def min(trees: List[BinomialTree[A]]): (BinomialTree[A], List[BinomialTree[A]]) =
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
 * Children are stored in strictly monotonically decreasing order of rank A tree of rank r will
 * have children of ranks r-1, r-2, ..., 1
 */
private[std] final case class BinomialTree[A](
    rank: Int,
    value: A,
    children: List[BinomialTree[A]]) {

  /**
   * Link two trees of rank r to produce a tree of rank r + 1
   */
  def link(other: BinomialTree[A])(implicit Ord: Order[A]): BinomialTree[A] = {
    if (Ord.lteqv(value, other.value))
      BinomialTree(rank + 1, value, other :: children)
    else BinomialTree(rank + 1, other.value, this :: other.children)
  }

}
