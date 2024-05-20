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

/*
 * These tests have been inspired by and adapted from `monix-catnap`'s `ConcurrentQueueSuite`, available at
 * https://github.com/monix/monix/blob/series/3.x/monix-catnap/shared/src/test/scala/monix/catnap/ConcurrentQueueSuite.scala.
 */

package cats.effect.std.internal

import cats.Order

import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Arbitrary.arbitrary
import org.specs2.ScalaCheck
import org.specs2.mutable.Specification

class BinomialHeapSpec extends Specification with ScalaCheck {

  implicit val orderForInt: Order[Int] = Order.fromLessThan((x, y) => x < y)

  "Binomial heap" should {

    "dequeue by priority" in prop { (elems: List[Int]) =>
      val heap = buildHeap(elems)

      toList(heap) must beEqualTo(elems.sorted)
    }

    /**
     * The root of a heap must be <= any of its children and all of its children must also be
     * heaps
     */
    "maintain the heap property" in prop { (ops: List[Op[Int]]) =>
      val heap = Op.toHeap(ops)

      heap.trees.forall(validHeap(_)) must beTrue
    }

    /**
     * The rank of the top-level trees should be strictly monotonically increasing, where the
     * rank of a tree is defined as the height of the tree ie the number of nodes on the longest
     * path from the root to a leaf. There is one binomial tree for each nonzero bit in the
     * binary representation of the number of elements n
     *
     * The children of a top-level tree of rank i should be trees of monotonically decreasing
     * rank i-1, i-2, ..., 1
     */
    "maintain correct subtree ranks" in prop { (ops: List[Op[Int]]) =>
      val heap = Op.toHeap(ops)

      var currentRank = 0
      heap.trees.forall { t =>
        val r = rank(t)
        r must beGreaterThan(currentRank)
        currentRank = r
        checkRank(r, t)
      }
    }

  }

  /**
   * The root of a heap must be <= any of its children and all of its children must also be
   * heaps
   */
  private def validHeap[A](tree: BinomialTree[A])(implicit Ord: Order[A]): Boolean = {
    def validHeap(parent: A, tree: BinomialTree[A]): Boolean =
      Ord.lteqv(parent, tree.value) && tree.children.forall(validHeap(tree.value, _))

    tree.children match {
      case Nil => true
      case cs => cs.forall(validHeap(tree.value, _))
    }
  }

  private def buildHeap[A: Order](elems: List[A]): BinomialHeap[A] =
    elems.foldLeft(BinomialHeap.empty[A]) { (heap, e) => heap.insert(e) }

  private def toList[A](heap: BinomialHeap[A]): List[A] =
    heap.tryTake match {
      case (rest, Some(a)) => a :: toList(rest)
      case _ => Nil
    }

  private def rank[A](tree: BinomialTree[A]): Int =
    tree.children match {
      case Nil => 1
      case h :: _ => 1 + rank(h)
    }

  /**
   * A tree of rank i should have children of rank i-1, i-2, ..., 1 in that order
   */
  private def checkRank[A](rank: Int, tree: BinomialTree[A]): Boolean =
    tree.children match {
      case Nil => rank == 1
      case cs =>
        List.range(0, rank).reverse.zip(cs).forall { case (r, t) => checkRank(r, t) }
    }

  sealed trait Op[+A]
  case class Insert[A](a: A) extends Op[A]
  case object Take extends Op[Nothing]

  object Op {
    implicit def arbitraryForOp[A: Arbitrary]: Arbitrary[Op[A]] =
      Arbitrary(
        Gen.frequency(
          (1, Gen.const(Take)),
          (3, arbitrary[A].map(Insert(_))) // Bias towards insert to generate non-trivial heaps
        )
      )

    def toHeap[A: Order](ops: List[Op[A]]) =
      ops.foldLeft(BinomialHeap.empty[A]) { (heap, op) =>
        op match {
          case Insert(a) => heap.insert(a)
          case Take => heap.tryTake._1
        }

      }
  }

}
