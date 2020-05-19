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

import cats.Eq

import org.scalacheck.Prop
import org.scalacheck.util.Pretty

sealed trait IsEq[A] {

  def ||(rhs: IsEq[A]): IsEq[A] = IsEq.Or(this, rhs)
  def &&(rhs: IsEq[A]): IsEq[A] = IsEq.And(this, rhs)

  def toProp(implicit A: Eq[A], pp: A => Pretty): Prop = this match {
    case IsEq.Assert(lhs, rhs) =>
      try {
        cats.laws.discipline.catsLawsIsEqToProp(cats.laws.IsEq(lhs, rhs))
      } catch {
        case soe: StackOverflowError =>
          soe.printStackTrace()
          throw soe
      }

    case IsEq.Or(lhs, rhs) =>
      lhs.toProp || rhs.toProp

    case IsEq.And(lhs, rhs) =>
      lhs.toProp && rhs.toProp
  }
}

object IsEq {

  def apply[A](lhs: A, rhs: A): IsEq[A] =
    Assert(lhs, rhs)

  implicit def toProp[A: Eq](isEq: IsEq[A])(implicit pp: A => Pretty): Prop =
    isEq.toProp

  final case class Assert[A](lhs: A, rhs: A) extends IsEq[A]
  final case class Or[A](lhs: IsEq[A], rhs: IsEq[A]) extends IsEq[A]
  final case class And[A](lhs: IsEq[A], rhs: IsEq[A]) extends IsEq[A]
}
