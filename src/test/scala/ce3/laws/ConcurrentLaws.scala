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

  def raceLeftCanceledYields[A](fa: F[A]) =
    F.race(F.canceled(()), fa) <-> fa.map(_.asRight[Unit])

  def raceRightCanceledYields[A](fa: F[A]) =
    F.race(fa, F.canceled(())) <-> fa.map(_.asLeft[Unit])

  // I really like these laws, since they relate cede to timing, but they're definitely nondeterministic
  /*def raceLeftCedeYields[A](a: A) =
    F.race(F.cede, F.pure(a)) <-> F.pure(Right(a))*/

  /*def raceRightCedeYields[A](a: A) =
    F.race(F.pure(a), F.cede) <-> F.pure(Left(a))*/

  def fiberPureIsCompletedPure[A](a: A) =
    F.start(F.pure(a)).flatMap(_.join) <-> F.pure(Outcome.Completed(F.pure(a)))

  def fiberErrorIsErrored(e: E) =
    F.start(F.raiseError[Unit](e)).flatMap(_.join) <-> F.pure(Outcome.Errored(e))

  def fiberCancelationIsCanceled =
    F.start(F.never[Unit]).flatMap(f => f.cancel >> f.join) <-> F.pure(Outcome.Canceled)

  def fiberOfCanceledIsCanceled =
    F.start(F.canceled(())).flatMap(_.join) <-> F.pure(Outcome.Canceled)

  def fiberJoinOfNeverIsNever =
    F.start(F.never[Unit]).flatMap(_.join) <-> F.never[Outcome[F, E, Unit]]

  def fiberStartOfNeverIsUnit =
    F.start(F.never[Unit]).void <-> F.unit

  def neverDistributesOverFlatMapLeft[A](fa: F[A]) =
    F.never >> fa <-> F.never[A]

  def uncancelablePollIsIdentity[A](fa: F[A]) =
    F.uncancelable(_(fa)) <-> fa

  // TODO find a way to do this without race conditions
  /*def uncancelableFiberBodyWillComplete[A](fa: F[A]) =
    F.start(F.uncancelable(_ => fa)).flatMap(f => F.cede >> f.cancel >> f.join) <->
      F.uncancelable(_ => fa.attempt).map(Outcome.fromEither[F, E, A](_))*/

  def uncancelableCancelationCancels =
    F.start(F.never[Unit]).flatMap(f => F.uncancelable(_ => f.cancel) >> f.join) <-> F.pure(Outcome.Canceled)

  def uncancelableOfCanceledIsPure[A](a: A) =
    F.uncancelable(_ => F.canceled(a)) <-> F.pure(a)

  def uncancelableRaceIsUncancelable[A](a: A) =
    F.uncancelable(_ => F.race(F.never[Unit], F.canceled(a))) <-> F.pure(a.asRight[Unit])

  def uncancelableStartIsCancelable =
    F.uncancelable(_ => F.start(F.canceled(())).flatMap(_.join)) <-> F.pure(Outcome.Canceled)

  def canceledDistributesOverFlatMapLeft[A](fa: F[A]) =
    F.canceled(()) >> fa.void <-> F.canceled(())
}

object ConcurrentLaws {
  def apply[F[_], E](implicit F0: Concurrent[F, E]): ConcurrentLaws[F, E] =
    new ConcurrentLaws[F, E] { val F = F0 }
}
