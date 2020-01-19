/*
 * Copyright 2020 Daniel Spiewak
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

package ce3
package laws

import cats.MonadError
import cats.implicits._
import cats.laws.MonadErrorLaws

trait BracketLaws[F[_], E] extends MonadErrorLaws[F, E] {

  implicit val F: Bracket[F, E]

  import F.CaseInstance

  def bracketPureCoherence[A, B](acq: F[A], f: A => B, release: F.Case[B] => F[Unit]) =
    F.bracketCase(acq)(a => F.pure(f(a)))((_, c) => release(c)) <->
      F.bracketCase(acq)(a => F.pure(f(a)))((a, _) => release(CaseInstance.pure(f(a))))

  def bracketErrorCoherence[A](acq: F[A], f: A => E, release: F.Case[Unit] => F[Unit]) =
    F.bracketCase(acq)(a => F.raiseError[Unit](f(a)))((_, c) => release(c)) <->
      F.bracketCase(acq)(a => F.raiseError[Unit](f(a)))((a, _) => release(CaseInstance.raiseError(f(a))))

  def bracketAcquireErrorIdentity[A, B](e: E, f: A => F[B], release: F[Unit]) =
    F.bracketCase(F.raiseError[A](e))(f)((_, _) => release) <-> F.raiseError[B](e)

  def bracketReleaseErrorIgnore(e: E) =
    F.bracketCase(F.unit)(_ => F.unit)((_, _) => F.raiseError[Unit](e)) <-> F.unit

  def bracketOnCaseDefinedByBracketCase[A](fa: F[A], body: F[Unit], p: F.Case[A] => Boolean) =
    F.onCase(fa, body)(p) <-> F.bracketCase(F.unit)(_ => fa)((_, c) => if (p(c)) body else F.unit)
}

object BracketLaws {
  def apply[F[_], E](implicit F0: Bracket[F, E]): BracketLaws[F, E] { val F: F0.type } =
    new BracketLaws[F, E] { val F: F0.type = F0 }
}
