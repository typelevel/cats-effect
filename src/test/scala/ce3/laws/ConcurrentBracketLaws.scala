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

trait ConcurrentBracketLaws[F[_], E] extends ConcurrentLaws[F, E] with BracketLaws[F, E] {

  implicit val F: Concurrent[F, E] with Bracket[F, E]

  def bracketCanceledReleases[A, B](acq: F[A], release: F[Unit], b: B) = {
    F.bracketCase(acq)(_ => F.canceled(b)) {
      case (a, Outcome.Canceled) => release
      case _ => F.unit
    } <-> (acq >> F.uncancelable(_ => release.attempt) >> F.canceled(b))
  }

  def bracketUncancelableFlatMapIdentity[A, B](acq: F[A], use: A => F[B], release: (A, Outcome[F, E, B]) => F[Unit]) = {
    val identity = F uncancelable { poll =>
      acq flatMap { a =>
        val finalized = F.onCase(poll(use(a)), release(a, Outcome.Canceled))(Outcome.Canceled ==)
        val handled = finalized.handleErrorWith(e => release(a, Outcome.Errored(e)).attempt >> F.raiseError(e))
        handled.flatMap(b => release(a, Outcome.Completed(F.pure(b))).attempt.as(b))
      }
    }

    F.bracketCase(acq)(use)(release) <-> identity
  }

  // TODO cancel a fiber in uncancelable still cancels
  // TODO cancel a fiber in a bracket body which errors
}

object ConcurrentBracketLaws {
  def apply[F[_], E](implicit F0: Concurrent[F, E] with Bracket[F, E]): ConcurrentBracketLaws[F, E] =
    new ConcurrentBracketLaws[F, E] { val F = F0 }
}
