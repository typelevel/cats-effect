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

/*
 * These tests have been inspired by and adapted from `monix-catnap`'s `ConcurrentQueueSuite`, available at
 * https://github.com/monix/monix/blob/series/3.x/monix-catnap/shared/src/test/scala/monix/catnap/ConcurrentQueueSuite.scala.
 */

package cats.effect.std.internal

import cats.Order

import org.specs2.mutable.Specification
import org.specs2.ScalaCheck

class BinomialHeapSpec extends Specification with ScalaCheck {

  implicit val orderForInt: Order[Int] = Order.fromLessThan((x, y) => x < y)

  "Binomial heap" should {

    "dequeue by priority" in prop { elems: List[Int] =>
      val heap = buildHeap(elems)

      toList(heap) must beEqualTo(elems.sorted)
    }

    "maintain the heap property" in prop { elems: List[Int] =>
      val heap = buildHeap(elems)

      heap.trees.forall(validHeap(_)) must beTrue
    }

    /**
     * The rank of the top-level trees should be strictly monotonically increasing.
     * There is one binomial tree for each nonzero bit in the binary representation
     * of the number of elements n
     *
     * The children of a top-level tree of rank i
     * should be trees of monotonically decreasing rank
     * i-1, i-2, ..., 1
     */
    "maintain correct subtree ranks" in prop { elems: List[Int] =>
      val heap = buildHeap(elems)

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
   * The root of a heap must be <= any of its children and all of its children
   * must also be heaps
   */
  private def validHeap[A: Order](tree: BinomialTree[A]): Boolean = {
    def validHeap[A](parent: A, tree: BinomialTree[A])(implicit Ord: Order[A]): Boolean =
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
   * A tree of rank i should have children of rank i-1, i-2, ..., 1
   * in that order
   */
  private def checkRank[A](rank: Int, tree: BinomialTree[A]): Boolean =
    tree.children match {
      case Nil => rank == 1
      case cs =>
        List.range(0, rank).reverse.zip(cs).forall {
          case (r, t) => checkRank(r, t)
        }
    }

}
