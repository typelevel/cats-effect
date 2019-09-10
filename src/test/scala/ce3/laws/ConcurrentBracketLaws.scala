/*
 * Copyright 2019 Daniel Spiewak
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

trait ConcurrentLaws[F[_], E] extends MonadErrorLaws[F, E] {

  implicit val F: Concurrent[F, E]

  def racePairLeftErrorYields[A](fa: F[A], e: E) = {
    val fe = F.raiseError[Unit](e)
    F.racePair(fe, fa) <-> F.start(fe).flatMap(f => fa.map(a => Right((f, a))))
  }

  def racePairRightErrorYields[A](fa: F[A], e: E) = {
    val fe = F.raiseError[Unit](e)
    F.racePair(fa, fe) <-> F.start(fe).flatMap(f => fa.map(a => Left((a, f))))
  }

  def racePairLeftCanceledYields[A](fa: F[A]) = {
    val fc = F.canceled[Unit]
    F.racePair(fc, fa) <-> F.start(fc).flatMap(f => fa.map(a => Right((f, a))))
  }

  def racePairRightCanceledYields[A](fa: F[A]) = {
    val fc = F.canceled[Unit]
    F.racePair(fa, fc) <-> F.start(fc).flatMap(f => fa.map(a => Left((a, f))))
  }

  def fiberCancelationIsCanceled[A](body: F[A]) =
    F.start(body).flatMap(f => f.cancel *> f.join) <-> F.pure(ExitCase.Canceled)

  def fiberOfCanceledIsCanceled =
    F.start(F.canceled[Unit]).flatMap(_.join) <-> F.pure(ExitCase.Canceled)
}

trait ConcurrentBracketLaws[F[_], E] extends ConcurrentLaws[F, E] with BracketLaws[F, E] {

  implicit val F: Concurrent[F, E] with Bracket[F, E]

  def bracketCanceledReleases[A, B](acq: F[A], release: F[Unit], e: E) = {
    F.bracketCase(acq)(_ => F.canceled[B]) {
      case (a, ExitCase.Canceled) => release
      case _ => F.raiseError[Unit](e)
    } <-> (acq.flatMap(_ => release) *> F.canceled[B])
  }

  def bracketProtectSuppressesCancelation[A, B](acq: F[A], use: A => F[B], cancel: F[Unit], completed: B => F[Unit]) = {
    val result = F.bracketCase(acq)(a => F.uncancelable(use(a))) {
      case (_, ExitCase.Canceled) => cancel
      case (_, ExitCase.Completed(b)) => completed(b)
      case (_, ExitCase.Errored(e)) => F.raiseError(e)
    }

    val completes = result <-> (acq.flatMap(use).flatMap(b => completed(b).as(b)))
    val aborts = result <-> F.canceled[B]

    completes || aborts
  }

  // TODO cancel a fiber in uncancelable still cancels
  // TODO cancel a fiber in a bracket body which errors
}

object ConcurrentBracketLaws {
  def apply[F[_], E](implicit F0: Concurrent[F, E] with Bracket[F, E]): ConcurrentBracketLaws[F, E] =
    new ConcurrentBracketLaws[F, E] { val F = F0 }
}
