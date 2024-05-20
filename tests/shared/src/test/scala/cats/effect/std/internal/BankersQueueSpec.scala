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

package cats.effect
package std.internal

import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Arbitrary.arbitrary
import org.specs2.ScalaCheck
import org.specs2.mutable.Specification

class BankersQueueSpec extends Specification with ScalaCheck {

  "bankers queue" should {
    /*
     * frontLen <= rebalanceConstant * backLen + 1
     * backLen <= rebalanceConstant * = frontLen + 1
     */
    "maintain size invariants" in prop { (ops: List[Op[Int]]) =>
      val queue = Op.fold(ops)

      queue.frontLen must beLessThanOrEqualTo(
        queue.backLen * BankersQueue.rebalanceConstant + 1)
      queue.backLen must beLessThanOrEqualTo(
        queue.frontLen * BankersQueue.rebalanceConstant + 1)
    }

    "dequeue in order from front" in prop { (elems: List[Int]) =>
      val queue = buildQueue(elems)

      toListFromFront(queue) must beEqualTo(elems)
    }

    "dequeue in order from back" in prop { (elems: List[Int]) =>
      val queue = buildQueue(elems)

      toListFromBack(queue) must beEqualTo(elems.reverse)
    }

    "reverse" in prop { (elems: List[Int]) =>
      val queue = buildQueue(elems)

      toListFromFront(queue.reverse) must beEqualTo(elems.reverse)
    }
  }

  private def buildQueue[A](elems: List[A]): BankersQueue[A] =
    elems.foldLeft(BankersQueue.empty[A]) { (heap, e) => heap.pushBack(e) }

  private def toListFromFront[A](queue: BankersQueue[A]): List[A] =
    queue.tryPopFront match {
      case (rest, Some(a)) => a :: toListFromFront(rest)
      case _ => Nil
    }

  private def toListFromBack[A](queue: BankersQueue[A]): List[A] =
    queue.tryPopBack match {
      case (rest, Some(a)) => a :: toListFromBack(rest)
      case _ => Nil
    }

  sealed trait Op[+A]
  case class PushFront[A](value: A) extends Op[A]
  case class PushBack[A](value: A) extends Op[A]
  case object PopFront extends Op[Nothing]
  case object PopBack extends Op[Nothing]

  object Op {
    implicit def arbitraryForOp[A: Arbitrary]: Arbitrary[Op[A]] =
      Arbitrary(
        Gen.frequency(
          (1, Gen.const(PopFront)),
          (1, Gen.const(PopBack)),
          (
            3, // Bias the generation to produce non-trivial queues
            arbitrary[A].map(PushFront(_))
          ),
          (3, arbitrary[A].map(PushBack(_)))
        )
      )

    def fold[A](ops: List[Op[A]]): BankersQueue[A] =
      ops.foldLeft(BankersQueue.empty[A]) { (queue, op) =>
        op match {
          case PushFront(a) => queue.pushFront(a)
          case PushBack(a) => queue.pushBack(a)
          case PopFront => if (queue.nonEmpty) queue.tryPopFront._1 else queue
          case PopBack => if (queue.nonEmpty) queue.tryPopBack._1 else queue
        }

      }
  }
}
