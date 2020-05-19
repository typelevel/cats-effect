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

import cats.MonadError
import cats.implicits._
import cats.laws.MonadErrorLaws

trait RegionLaws[R[_[_], _], F[_], E] extends MonadErrorLaws[R[F, ?], E] {

  implicit val F: Region[R, F, E]

  // TODO we can also define different identities for MonadError
  implicit val B: Bracket[F, E] {
    type Case[A] <: F.Case[A]
  }

  import F.CaseInstance

  def regionEmptyBracketPure[A, B](acq: F[A], fb: F[B], release: (A, F.Case[_]) => F[Unit]) =
    F.supersededBy(F.openCase(acq)(release), F.liftF(fb)) <-> F.liftF(B.bracketCase(acq)(B.pure(_))(release) *> fb)

  def regionNested[A, B, C](fa: F[A], f: A => F[B], fc: F[C], releaseA: (A, F.Case[_]) => F[Unit], releaseB: (B, F.Case[_]) => F[Unit]) =
    F.supersededBy(F.openCase(fa)(releaseA).flatMap(a => F.openCase(f(a))(releaseB)), F.liftF(fc)) <-> F.liftF(B.bracketCase(fa)(a => B.bracketCase(f(a))(B.pure(_))(releaseB))(releaseA) *> fc)

  def regionExtend[A, B](fa: F[A], f: A => F[B], release: A => F[Unit]) =
    F.open(fa)(release).flatMap(f.andThen(F.liftF(_))) <-> F.liftF(B.bracket(fa)(f)(release))

  def regionErrorCoherence[A](fa: F[A], f: A => E, release: (A, F.Case[_]) => F[Unit]) =
    F.openCase(fa)(release).flatMap(a => F.raiseError[Unit](f(a))) <-> F.liftF(B.bracket(fa)(a => B.raiseError[Unit](f(a)))(a => release(a, CaseInstance.raiseError[Unit](f(a)))))

  def regionLiftFOpenUnit[A](fa: F[A]) =
    F.liftF(fa) <-> F.open(fa)(_ => B.unit)
}

object RegionLaws {
  def apply[
      R[_[_], _],
      F[_],
      Case0[_],
      E](
    implicit
      F0: Region[R, F, E] { type Case[A] = Case0[A] },
      B0: Bracket[F, E] { type Case[A] = Case0[A] })    // this is legit-annoying
      : RegionLaws[R, F, E] =
    new RegionLaws[R, F, E] { val F = F0; val B = B0 }
}
