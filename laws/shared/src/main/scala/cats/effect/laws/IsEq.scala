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

package cats.effect.laws

import cats.{Applicative, Eq, Group, Order}
import cats.syntax.all._

import org.scalacheck.Prop
import org.scalacheck.util.Pretty

sealed trait IsEq[A] {

  def toPropTolerant[F[_], B](
      implicit ev: A =:= F[B],
      F: Applicative[F],
      ord: Order[F[B]],
      g: Group[B],
      tolerance: Tolerance[B],
      pp: A => Pretty): Prop =
    this match {
      case IsEq.Assert(lhs0, rhs0) =>
        val lhs = ev(lhs0)
        val rhs = ev(rhs0)

        if (lhs <= rhs && (rhs, lhs).mapN(_ |-| _) <= tolerance.value.pure[F]) {
          Prop.proved
        } else if (lhs > rhs && (lhs, rhs).mapN(_ |-| _) <= tolerance.value.pure[F]) {
          Prop.proved
        } else {
          Prop.falsified :| {
            val exp = Pretty.pretty[A](rhs0, Pretty.Params(0))
            val act = Pretty.pretty[A](lhs0, Pretty.Params(0))
            s"Expected: $exp ± ${tolerance.value}\n" + s"Received: $act"
          }
        }
    }

  def toProp(implicit A: Eq[A], pp: A => Pretty): Prop =
    this match {
      case IsEq.Assert(lhs, rhs) =>
        try {
          cats.laws.discipline.catsLawsIsEqToProp(cats.laws.IsEq(lhs, rhs))
        } catch {
          case soe: StackOverflowError =>
            soe.printStackTrace()
            throw soe
        }
    }
}

private[laws] trait IsEqLowPriorityImplicits {
  implicit def toProp[A: Eq](isEq: IsEq[A])(implicit pp: A => Pretty): Prop =
    isEq.toProp
}

object IsEq extends IsEqLowPriorityImplicits {

  implicit def toPropTolerant[F[_], A](isEq: IsEq[F[A]])(
      implicit F: Applicative[F],
      ord: Order[F[A]],
      g: Group[A],
      tolerance: Tolerance[A],
      pp: F[A] => Pretty): Prop =
    isEq.toPropTolerant

  def apply[A](lhs: A, rhs: A): IsEq[A] =
    Assert(lhs, rhs)

  final case class Assert[A](lhs: A, rhs: A) extends IsEq[A]
}
