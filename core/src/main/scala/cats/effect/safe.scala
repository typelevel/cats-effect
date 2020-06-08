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

import cats.{ApplicativeError, MonadError}

// represents the type Bracket | Region
sealed trait Safe[F[_], E] extends MonadError[F, E] {
  // inverts the contravariance, allowing a lawful bracket without discussing cancelation until Concurrent
  type Case[A]

  implicit def CaseInstance: ApplicativeError[Case, E]
}

trait Bracket[F[_], E] extends Safe[F, E] {

  def bracketCase[A, B](
      acquire: F[A])(
      use: A => F[B])(
      release: (A, Case[B]) => F[Unit])
      : F[B]

  def bracket[A, B](
      acquire: F[A])(
      use: A => F[B])(
      release: A => F[Unit])
      : F[B] =
    bracketCase(acquire)(use)((a, _) => release(a))

  def onCase[A](fa: F[A])(pf: PartialFunction[Case[A], F[Unit]]): F[A] =
    bracketCase(unit)(_ => fa)((_, c) => pf.lift(c).getOrElse(unit))
}

object Bracket {
  type Aux[F[_], E, Case0[_]] = Bracket[F, E] { type Case[A] = Case0[A] }
  type Aux2[F[_], E, Case0[_, _]] = Bracket[F, E] { type Case[A] = Case0[E, A] }

  def apply[F[_], E](implicit F: Bracket[F, E]): F.type = F
}

trait Region[R[_[_], _], F[_], E] extends Safe[R[F, *], E] {

  def openCase[A, e](acquire: F[A])(release: (A, Case[e]) => F[Unit]): R[F, A]

  def open[A](acquire: F[A])(release: A => F[Unit]): R[F, A] =
    openCase(acquire)((a: A, _: Case[Unit]) => release(a))

  def liftF[A](fa: F[A]): R[F, A]

  // this is analogous to *>, but with more constrained laws (closing the resource scope)
  def supersededBy[B, e](rfa: R[F, e], rfb: R[F, B]): R[F, B]

  // this is analogous to void, but it closes the resource scope
  def close[e](rfa: R[F, e]): R[F, Unit] = supersededBy(rfa, unit)
}

object Region {
  type Aux[R[_[_], _], F[_], E, Case0[_]] = Region[R, F, E] { type Case[A] = Case0[A] }
  type Aux2[R[_[_], _], F[_], E, Case0[_, _]] = Region[R, F, E] { type Case[A] = Case0[E, A] }
}
