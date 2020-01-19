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

trait ConcurrentLaws[F[_], E] extends MonadErrorLaws[F, E] {

  implicit val F: Concurrent[F, E]

  def raceIsRacePairCancelIdentity[A, B](fa: F[A], fb: F[B]) = {
    val identity = F.racePair(fa, fb) flatMap {
      case Left((a, f)) => f.cancel.as(a.asLeft[B])
      case Right((f, b)) => f.cancel.as(b.asRight[A])
    }

    F.race(fa, fb) <-> identity
  }

  def raceLeftErrorYields[A](fa: F[A], e: E) =
    F.race(F.raiseError[Unit](e), fa) <-> fa.map(_.asRight[Unit])

  def raceRightErrorYields[A](fa: F[A], e: E) =
    F.race(fa, F.raiseError[Unit](e)) <-> fa.map(_.asLeft[Unit])

  def raceLeftCanceledYields[A](fa: F[A]) =
    F.race(F.canceled(()), fa) <-> fa.map(_.asRight[Unit])

  def raceRightCanceledYields[A](fa: F[A]) =
    F.race(fa, F.canceled(())) <-> fa.map(_.asLeft[Unit])

  def fiberPureIsCompletedPure[A](a: A) =
    F.start(F.pure(a)).flatMap(f => f.cancel >> f.join) <-> F.pure(Outcome.Completed(F.pure(a)))

  def fiberErrorIsErrored(e: E) =
    F.start(F.raiseError[Unit](e)).flatMap(f => f.cancel >> f.join) <-> F.pure(Outcome.Errored(e))

  def fiberCancelationIsCanceled =
    F.start(F.never[Unit]).flatMap(f => f.cancel >> f.join) <-> F.pure(Outcome.Canceled)

  def fiberOfCanceledIsCanceled =
    F.start(F.canceled(())).flatMap(_.join) <-> F.pure(Outcome.Canceled)

  def uncancelablePollIsIdentity[A](fa: F[A]) =
    F.uncancelable(_(fa)) <-> fa

  def uncancelableFiberBodyWillComplete[A](fa: F[A]) =
    F.start(F.uncancelable(_ => fa)).flatMap(f => f.cancel >> f.join) <-> fa.map(a => Outcome.Completed(a.pure[F]))

  def uncancelableOfCanceledIsPure[A](a: A) =
    F.uncancelable(_ => F.canceled(a)) <-> F.pure(a)
}

object ConcurrentLaws {
  def apply[F[_], E](implicit F0: Concurrent[F, E]): ConcurrentLaws[F, E] =
    new ConcurrentLaws[F, E] { val F = F0 }
}
