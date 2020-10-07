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

import cats.effect.kernel.{GenSpawn, Outcome}
import cats.syntax.all._

trait GenSpawnLaws[F[_], E] extends MonadCancelLaws[F, E] {

  implicit val F: GenSpawn[F, E]

  // we need to phrase this in terms of never because we can't *evaluate* laws which rely on nondetermnistic substitutability
  def raceDerivesFromRacePairLeft[A, B](fa: F[A]) = {
    val results: F[Either[A, B]] = F uncancelable { demask =>
      F.flatMap(F.racePair(fa, F.never[B])) {
        case Left((oc, f)) =>
          oc match {
            case Outcome.Succeeded(fa) => F.productR(f.cancel)(F.map(fa)(Left(_)))
            case Outcome.Errored(ea) => F.productR(f.cancel)(F.raiseError(ea))
            case Outcome.Canceled() =>
              F.flatMap(F.onCancel(demask(f.join), f.cancel)) {
                case Outcome.Succeeded(fb) => F.map(fb)(Right(_))
                case Outcome.Errored(eb) => F.raiseError(eb)
                case Outcome.Canceled() => F.productR(F.canceled)(F.never)
              }
          }
        case Right((f, oc)) =>
          oc match {
            case Outcome.Succeeded(fb) => F.productR(f.cancel)(F.map(fb)(Right(_)))
            case Outcome.Errored(eb) => F.productR(f.cancel)(F.raiseError(eb))
            case Outcome.Canceled() =>
              F.flatMap(F.onCancel(demask(f.join), f.cancel)) {
                case Outcome.Succeeded(fa) => F.map(fa)(Left(_))
                case Outcome.Errored(ea) => F.raiseError(ea)
                case Outcome.Canceled() => F.productR(F.canceled)(F.never)
              }
          }
      }
    }

    F.race(fa, F.never[B]) <-> results
  }

  def raceDerivesFromRacePairRight[A, B](fb: F[B]) = {
    val results: F[Either[A, B]] = F uncancelable { demask =>
      F.flatMap(F.racePair(F.never[A], fb)) {
        case Left((oc, f)) =>
          oc match {
            case Outcome.Succeeded(fa) => F.productR(f.cancel)(F.map(fa)(Left(_)))
            case Outcome.Errored(ea) => F.productR(f.cancel)(F.raiseError(ea))
            case Outcome.Canceled() =>
              F.flatMap(F.onCancel(demask(f.join), f.cancel)) {
                case Outcome.Succeeded(fb) => F.map(fb)(Right(_))
                case Outcome.Errored(eb) => F.raiseError(eb)
                case Outcome.Canceled() => F.productR(F.canceled)(F.never)
              }
          }
        case Right((f, oc)) =>
          oc match {
            case Outcome.Succeeded(fb) => F.productR(f.cancel)(F.map(fb)(Right(_)))
            case Outcome.Errored(eb) => F.productR(f.cancel)(F.raiseError(eb))
            case Outcome.Canceled() =>
              F.flatMap(F.onCancel(demask(f.join), f.cancel)) {
                case Outcome.Succeeded(fa) => F.map(fa)(Left(_))
                case Outcome.Errored(ea) => F.raiseError(ea)
                case Outcome.Canceled() => F.productR(F.canceled)(F.never)
              }
          }
      }
    }

    F.race(F.never[A], fb) <-> results
  }

  def raceCanceledIdentityLeft[A](fa: F[A]) =
    F.race(F.canceled, fa) <-> fa.map(_.asRight[Unit])

  def raceCanceledIdentityRight[A](fa: F[A]) =
    F.race(fa, F.canceled) <-> fa.map(_.asLeft[Unit])

  def raceNeverIdentityLeft[A](fa: F[A]) =
    F.race(F.never[Unit], fa) <-> fa.map(_.asRight[Unit])

  def raceNeverIdentityRight[A](fa: F[A]) =
    F.race(fa, F.never[Unit]) <-> fa.map(_.asLeft[Unit])

  // I really like these laws, since they relate cede to timing, but they're definitely nondeterministic
  /*def raceLeftCedeYields[A](a: A) =
    F.race(F.cede, F.pure(a)) <-> F.pure(Right(a))*/

  /*def raceRightCedeYields[A](a: A) =
    F.race(F.pure(a), F.cede) <-> F.pure(Left(a))*/

  def fiberPureIsOutcomeCompletedPure[A](a: A) =
    F.start(F.pure(a)).flatMap(_.join) <-> F.pure(Outcome.Succeeded(F.pure(a)))

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

  def forceRNeverIsNever[A](fa: F[A]) =
    F.forceR(F.never)(fa) <-> F.never
}

object GenSpawnLaws {
  def apply[F[_], E](implicit F0: GenSpawn[F, E]): GenSpawnLaws[F, E] =
    new GenSpawnLaws[F, E] { val F = F0 }
}
