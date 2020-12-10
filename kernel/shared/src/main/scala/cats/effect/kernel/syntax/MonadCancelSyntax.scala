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

package cats.effect.kernel.syntax

import cats.effect.kernel.{MonadCancel, Outcome}

trait MonadCancelSyntax {

  implicit def monadCancelOps[F[_], A, E](
      wrapped: F[A]
  ): MonadCancelOps[F, A, E] =
    new MonadCancelOps(wrapped)
}

final class MonadCancelOps[F[_], A, E] private[syntax] (private[syntax] val wrapped: F[A])
    extends AnyVal {

  def forceR[B](fb: F[B])(implicit F: MonadCancel[F, E]): F[B] =
    F.forceR(wrapped)(fb)

  def !>[B](fb: F[B])(implicit F: MonadCancel[F, E]): F[B] =
    forceR(fb)

  def uncancelable(implicit F: MonadCancel[F, E]): F[A] =
    F.uncancelable_(wrapped)

  def onCancel(fin: F[Unit])(implicit F: MonadCancel[F, E]): F[A] =
    F.onCancel(wrapped, fin)

  def guarantee(fin: F[Unit])(implicit F: MonadCancel[F, E]): F[A] =
    F.guarantee(wrapped, fin)

  def guaranteeCase(fin: Outcome[F, E, A] => F[Unit])(implicit F: MonadCancel[F, E]): F[A] =
    F.guaranteeCase(wrapped)(fin)

  def bracket[B](use: A => F[B])(release: A => F[Unit])(implicit F: MonadCancel[F, E]): F[B] =
    F.bracket(wrapped)(use)(release)

  def bracketCase[B](use: A => F[B])(release: (A, Outcome[F, E, B]) => F[Unit])(
      implicit F: MonadCancel[F, E]): F[B] =
    F.bracketCase(wrapped)(use)(release)
}
