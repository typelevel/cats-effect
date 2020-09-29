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

package cats.effect
package laws

import cats.effect.kernel.MonadCancel
import cats.syntax.all._
import cats.laws.MonadErrorLaws

trait MonadCancelLaws[F[_], E] extends MonadErrorLaws[F, E] {

  implicit val F: MonadCancel[F, E]

  // note that this implies the nested case as well
  def uncancelablePollIsIdentity[A](fa: F[A]) =
    F.uncancelable(_(fa)) <-> fa

  def uncancelableIgnoredPollEliminatesNesting[A](fa: F[A]) =
    F.uncancelable(_ => F.uncancelable(_ => fa)) <-> F.uncancelable(_ => fa)

  // this law shows that inverted polls do not apply
  def uncancelablePollInverseNestIsUncancelable[A](fa: F[A]) =
    F.uncancelable(op => F.uncancelable(ip => op(ip(fa)))) <-> F.uncancelable(_ => fa)

  // TODO F.uncancelable(p => F.canceled >> p(fa) >> fb) <-> F.uncancelable(p => p(F.canceled >> fa) >> fb)

  def uncancelableCanceledAssociatesRightOverFlatMap[A](a: A, f: A => F[Unit]) =
    F.uncancelable(_ => F.canceled.as(a).flatMap(f)) <->
      F.forceR(F.uncancelable(_ => f(a)))(F.canceled)

  def canceledAssociatesLeftOverFlatMap[A](fa: F[A]) =
    F.canceled >> fa.void <-> F.canceled

  def canceledSequencesOnCancelInOrder(fin1: F[Unit], fin2: F[Unit]) =
    F.onCancel(F.onCancel(F.canceled, fin1), fin2) <->
      F.forceR(F.uncancelable(_ => F.forceR(fin1)(fin2)))(F.canceled)

  def uncancelableEliminatesOnCancel[A](fa: F[A], fin: F[Unit]) =
    F.uncancelable(_ => F.onCancel(fa, fin)) <-> F.uncancelable(_ => fa)

  def forceRDiscardsPure[A, B](a: A, fa: F[B]) =
    F.forceR(F.pure(a))(fa) <-> fa

  def forceRDiscardsError[A](e: E, fa: F[A]) =
    F.forceR(F.raiseError(e))(fa) <-> fa

  def forceRCanceledShortCircuits[A](fa: F[A]) =
    F.forceR(F.canceled)(fa) <-> F.productR(F.canceled)(fa)

  def uncancelableFinalizers[A](fin: F[Unit]) =
    F.onCancel(F.canceled, F.uncancelable(_ => fin)) <-> F.onCancel(F.canceled, fin)

}

object MonadCancelLaws {
  def apply[F[_], E](implicit F0: MonadCancel[F, E]): MonadCancelLaws[F, E] =
    new MonadCancelLaws[F, E] { val F = F0 }
}
