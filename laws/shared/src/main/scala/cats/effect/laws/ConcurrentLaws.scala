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

import cats.effect.kernel.{Concurrent, Outcome}
import cats.implicits._
import cats.laws.MonadErrorLaws

trait ConcurrentLaws[F[_], E] extends MonadErrorLaws[F, E] {

  implicit val F: Concurrent[F, E]

  def raceIsRacePairCancelIdentityLeft[A](fa: F[A]) = {
    val identity = F.racePair(fa, F.never[Unit]).flatMap {
      case Left((a, f)) => f.cancel.as(a.asLeft[Unit])
      case Right((f, b)) => f.cancel.as(b.asRight[A])
    }

    F.race(fa, F.never[Unit]) <-> identity
  }

  def raceIsRacePairCancelIdentityRight[A](fa: F[A]) = {
    val identity = F.racePair(F.never[Unit], fa).flatMap {
      case Left((a, f)) => f.cancel.as(a.asLeft[A])
      case Right((f, b)) => f.cancel.as(b.asRight[Unit])
    }

    F.race(F.never[Unit], fa) <-> identity
  }

  def raceCanceledIdentityLeft[A](fa: F[A]) =
    F.race(F.canceled, fa) <-> fa.map(_.asRight[Unit])

  def raceCanceledIdentityRight[A](fa: F[A]) =
    F.race(fa, F.canceled) <-> fa.map(_.asLeft[Unit])

  def raceNeverIdentityAttemptLeft[A](fa: F[A]) =
    F.race(F.never[Unit], fa.attempt) <-> fa.attempt.map(_.asRight[Unit])

  def raceNeverIdentityAttemptRight[A](fa: F[A]) =
    F.race(fa.attempt, F.never[Unit]) <-> fa.attempt.map(_.asLeft[Unit])

  // I really like these laws, since they relate cede to timing, but they're definitely nondeterministic
  /*def raceLeftCedeYields[A](a: A) =
    F.race(F.cede, F.pure(a)) <-> F.pure(Right(a))*/

  /*def raceRightCedeYields[A](a: A) =
    F.race(F.pure(a), F.cede) <-> F.pure(Left(a))*/

  def fiberPureIsOutcomeCompletedPure[A](a: A) =
    F.start(F.pure(a)).flatMap(_.join) <-> F.pure(Outcome.Completed(F.pure(a)))

  def fiberErrorIsOutcomeErrored(e: E) =
    F.start(F.raiseError[Unit](e)).flatMap(_.join) <-> F.pure(Outcome.Errored(e))

  def fiberCancelationIsOutcomeCanceled =
    F.start(F.never[Unit]).flatMap(f => f.cancel >> f.join) <-> F.pure(Outcome.Canceled())

  def fiberCanceledIsOutcomeCanceled =
    F.start(F.canceled).flatMap(_.join) <-> F.pure(Outcome.Canceled())

  def fiberNeverIsNever =
    F.start(F.never[Unit]).flatMap(_.join) <-> F.never[Outcome[F, E, Unit]]

  def fiberStartOfNeverIsUnit =
    F.start(F.never[Unit]).void <-> F.unit

  def neverDominatesOverFlatMap[A](fa: F[A]) =
    F.never >> fa <-> F.never[A]

  // note that this implies the nested case as well
  def uncancelablePollIsIdentity[A](fa: F[A]) =
    F.uncancelable(_(fa)) <-> fa

  def uncancelableIgnoredPollEliminatesNesting[A](fa: F[A]) =
    F.uncancelable(_ => F.uncancelable(_ => fa)) <-> F.uncancelable(_ => fa)

  // this law shows that inverted polls do not apply
  def uncancelablePollInverseNestIsUncancelable[A](fa: F[A]) =
    F.uncancelable(op => F.uncancelable(ip => op(ip(fa)))) <-> F.uncancelable(_ => fa)

  // TODO PureConc *passes* this as-written... and shouldn't (never is masked, meaning cancelation needs to wait for it, meaning this *should* be <-> F.never)
  /*def uncancelableDistributesOverRaceAttemptLeft[A](fa: F[A]) =
    F.uncancelable(_ => F.race(fa.attempt, F.never[Unit])) <-> F.uncancelable(_ => fa.attempt.map(_.asLeft[Unit]))*/

  def uncancelableDistributesOverRaceAttemptLeft[A](fa: F[A]) =
    F.uncancelable(p => F.race(fa.attempt, p(F.never[Unit]))) <-> F.uncancelable(_ =>
      fa.attempt.map(_.asLeft[Unit]))

  def uncancelableDistributesOverRaceAttemptRight[A](fa: F[A]) =
    F.uncancelable(p => F.race(p(F.never[Unit]), fa.attempt)) <-> F.uncancelable(_ =>
      fa.attempt.map(_.asRight[Unit]))

  def uncancelableRaceDisplacesCanceled =
    F.uncancelable(_ => F.race(F.never[Unit], F.canceled)).void <-> F.canceled

  def uncancelableRacePollCanceledIdentityLeft[A](fa: F[A]) =
    F.uncancelable(p => F.race(p(F.canceled), fa)) <-> F.uncancelable(_ =>
      fa.map(_.asRight[Unit]))

  def uncancelableRacePollCanceledIdentityRight[A](fa: F[A]) =
    F.uncancelable(p => F.race(fa, p(F.canceled))) <-> F.uncancelable(_ =>
      fa.map(_.asLeft[Unit]))

  def uncancelableCancelCancels =
    F.start(F.never[Unit]).flatMap(f => F.uncancelable(_ => f.cancel) >> f.join) <-> F.pure(
      Outcome.Canceled())

  def uncancelableStartIsCancelable =
    F.uncancelable(_ => F.start(F.never[Unit]).flatMap(f => f.cancel >> f.join)) <-> F.pure(
      Outcome.Canceled())

  // TODO F.uncancelable(p => F.canceled >> p(fa) >> fb) <-> F.uncancelable(p => p(F.canceled >> fa) >> fb)

  // the attempt here enforces the cancelation-dominates-over-errors semantic
  def uncancelableCanceledAssociatesRightOverFlatMap[A](a: A, f: A => F[Unit]) =
    F.uncancelable(_ => F.canceled.as(a).flatMap(f)) <-> (F.uncancelable(_ =>
      f(a).attempt) >> F.canceled)

  def canceledAssociatesLeftOverFlatMap[A](fa: F[A]) =
    F.canceled >> fa.void <-> F.canceled

  def canceledSequencesOnCancelInOrder(fin1: F[Unit], fin2: F[Unit]) =
    F.onCancel(F.onCancel(F.canceled, fin1), fin2) <-> (F.uncancelable(_ =>
      fin1.attempt >> fin2.attempt) >> F.canceled)

  def uncancelableEliminatesOnCancel[A](fa: F[A], fin: F[Unit]) =
    F.uncancelable(_ => F.onCancel(fa, fin)) <-> F.uncancelable(_ => fa)
}

object ConcurrentLaws {
  def apply[F[_], E](implicit F0: Concurrent[F, E]): ConcurrentLaws[F, E] =
    new ConcurrentLaws[F, E] { val F = F0 }
}
