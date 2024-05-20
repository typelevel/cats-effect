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

/**
 * A banker's dequeue a la Okasaki. The front list is in order, the back list is in reversed
 * order. Therefore (aside from the edge case where one of the lists is empty and the other is
 * of size 1) the first element of the queue is at the head of the front list and the last
 * element of the queue is at the head of the back list.
 *
 * Maintains the invariants that:
 *   - frontLen <= rebalanceConstant * backLen + 1
 *   - backLen <= rebalanceConstant * frontLen + 1
 */
private[std] final case class BankersQueue[A](
    front: List[A],
    frontLen: Int,
    back: List[A],
    backLen: Int) {
  import BankersQueue._

  def nonEmpty: Boolean = frontLen > 0 || backLen > 0

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
    if (backLen > 0) {
      BankersQueue(front, frontLen, back.tail, backLen - 1).rebalance() -> Some(back.head)
    } else if (frontLen > 0) BankersQueue(Nil, 0, back, backLen) -> Some(front.head)
    else this -> None

  def reverse: BankersQueue[A] = BankersQueue(back, backLen, front, frontLen)

  private def rebalance(): BankersQueue[A] =
    if (frontLen > rebalanceConstant * backLen + 1) {
      val i = (frontLen + backLen) / 2
      val j = frontLen + backLen - i
      val f = front.take(i)
      val b = back ++ front.drop(i).reverse
      BankersQueue(f, i, b, j)
    } else if (backLen > rebalanceConstant * frontLen + 1) {
      val i = (frontLen + backLen) / 2
      val j = frontLen + backLen - i
      val f = front ++ back.drop(j).reverse
      val b = back.take(j)
      BankersQueue(f, i, b, j)
    } else this

}

private[std] object BankersQueue {
  val rebalanceConstant = 2

  def empty[A]: BankersQueue[A] = BankersQueue(Nil, 0, Nil, 0)
}
