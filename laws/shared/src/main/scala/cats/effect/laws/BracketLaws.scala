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

import cats.effect.kernel.Bracket
import cats.implicits._
import cats.laws.MonadErrorLaws

trait BracketLaws[F[_], E] extends MonadErrorLaws[F, E] {

  implicit val F: Bracket[F, E]

  import F.CaseInstance

  def onCasePureCoherence[A](a: A, release: PartialFunction[F.Case[A], F[Unit]]) =
    F.onCase(F.pure(a))(release).void <->
      release.lift(CaseInstance.pure(a)).getOrElse(F.unit).attempt.void

  def onCaseErrorCoherence[A](e: E, release: PartialFunction[F.Case[A], F[Unit]]) =
    F.onCase(F.raiseError[A](e))(release).void <->
      (release.lift(CaseInstance.raiseError[A](e)).getOrElse(F.unit).attempt >> F.raiseError[Unit](e))

  def bracketAcquireErrorIdentity[A, B](e: E, f: A => F[B], release: F[Unit]) =
    F.bracketCase(F.raiseError[A](e))(f)((_, _) => release) <-> F.raiseError[B](e)

  def bracketReleaseErrorIgnore(e: E) =
    F.bracketCase(F.unit)(_ => F.unit)((_, _) => F.raiseError[Unit](e)) <-> F.unit

  def bracketBodyIdentity[A](fa: F[A]) =
    F.bracketCase(F.unit)(_ => fa)((_, _) => F.unit) <-> fa

  def onCaseDefinedByBracketCase[A](fa: F[A], pf: PartialFunction[F.Case[A], F[Unit]]) =
    F.onCase(fa)(pf) <-> F.bracketCase(F.unit)(_ => fa)((_, c) => pf.lift(c).getOrElse(F.unit))
}

object BracketLaws {
  def apply[F[_], E](implicit F0: Bracket[F, E]): BracketLaws[F, E] { val F: F0.type } =
    new BracketLaws[F, E] { val F: F0.type = F0 }
}
