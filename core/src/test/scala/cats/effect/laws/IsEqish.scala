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

package cats.effect.laws

import cats.{Group, Order}
import cats.implicits._

import org.scalacheck.Prop
import org.scalacheck.util.Pretty

sealed trait IsEqish[A] {

  def ||(rhs: IsEqish[A]): IsEqish[A] = IsEqish.Or(this, rhs)
  def &&(rhs: IsEqish[A]): IsEqish[A] = IsEqish.And(this, rhs)

  def toProp(implicit ord: Order[A], g: Group[A], tolerance: Tolerance[A], pp: A => Pretty): Prop = this match {
    case IsEqish.Assert(lhs, rhs) =>
      if (lhs <= rhs && (rhs |-| lhs) <= tolerance.value) {
        Prop.proved
      } else if (lhs > rhs && (lhs |-| rhs) <= tolerance.value) {
        Prop.proved
      } else {
        Prop.falsified :| {
          val exp = Pretty.pretty[A](rhs, Pretty.Params(0))
          val act = Pretty.pretty[A](lhs, Pretty.Params(0))
          s"Expected: $exp ± ${tolerance.value}\n" + s"Received: $act"
        }
      }

    case IsEqish.Or(lhs, rhs) =>
      lhs.toProp || rhs.toProp

    case IsEqish.And(lhs, rhs) =>
      lhs.toProp && rhs.toProp
  }
}

object IsEqish {

  def apply[A](lhs: A, rhs: A): IsEqish[A] =
    Assert(lhs, rhs)

  implicit def toProp[A: Order: Tolerance: Group](isEqish: IsEqish[A])(implicit pp: A => Pretty): Prop =
    isEqish.toProp

  final case class Assert[A](lhs: A, rhs: A) extends IsEqish[A]
  final case class Or[A](lhs: IsEqish[A], rhs: IsEqish[A]) extends IsEqish[A]
  final case class And[A](lhs: IsEqish[A], rhs: IsEqish[A]) extends IsEqish[A]
}
