/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

package cats.effect.syntax

import cats.effect.{Bracket, ExitCase}

trait BracketSyntax {
  implicit def catsEffectSyntaxBracket[F[_], A, E](fa: F[A])(implicit bracket: Bracket[F, E]): BracketOps[F, E, A] = {
    // Bracket instance here is required to ensure correct inference for E
    val _ = bracket
    new BracketOps[F, E, A](fa)
  }
}

final class BracketOps[F[_], E, A](val self: F[A]) extends AnyVal {
  def bracketCase[B](use: A => F[B])(release: (A, ExitCase[E]) => F[Unit])(implicit F: Bracket[F, E]): F[B] =
    F.bracketCase(self)(use)(release)

  def bracket[B](use: A => F[B])(release: A => F[Unit])(implicit F: Bracket[F, E]): F[B] =
    F.bracket(self)(use)(release)

  def guarantee(finalizer: F[Unit])(implicit F: Bracket[F, E]): F[A] =
    F.guarantee(self)(finalizer)

  def guaranteeCase(finalizer: ExitCase[E] => F[Unit])(implicit F: Bracket[F, E]): F[A] =
    F.guaranteeCase(self)(finalizer)

  def uncancelable(implicit F: Bracket[F, E]): F[A] =
    F.uncancelable(self)

  def onCancel(finalizer: F[Unit])(implicit F: Bracket[F, E]): F[A] =
    F.onCancel(self)(finalizer)
}
