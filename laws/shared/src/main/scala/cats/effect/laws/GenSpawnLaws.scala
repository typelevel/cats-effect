/*
 * Copyright 2020-2024 Typelevel
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

trait GenSpawnLaws[F[_], E] extends MonadCancelLaws[F, E] with UniqueLaws[F] {

  implicit val F: GenSpawn[F, E]

  // we need to phrase this in terms of never because we can't *evaluate* laws which rely on nondetermnistic substitutability
  def raceDerivesFromRacePairLeft[A, B](fa: F[A]) = {
    val results: F[Either[A, B]] = F uncancelable { poll =>
      F.flatMap(F.racePair(fa, F.never[B])) {
        case Left((oc, f)) =>
          oc match {
            case Outcome.Succeeded(fa) => F.productR(f.cancel)(F.map(fa)(Left(_)))
            case Outcome.Errored(ea) => F.productR(f.cancel)(F.raiseError(ea))
            case Outcome.Canceled() =>
              F.flatMap(f.cancel *> f.join) {
                case Outcome.Succeeded(fb) => F.map(fb)(Right(_))
                case Outcome.Errored(eb) => F.raiseError(eb)
                case Outcome.Canceled() => F.productR(poll(F.canceled))(F.never)
              }
          }
        case Right((f, oc)) =>
          oc match {
            case Outcome.Succeeded(fb) => F.productR(f.cancel)(F.map(fb)(Right(_)))
            case Outcome.Errored(eb) => F.productR(f.cancel)(F.raiseError(eb))
            case Outcome.Canceled() =>
              F.flatMap(f.cancel *> f.join) {
                case Outcome.Succeeded(fa) => F.map(fa)(Left(_))
                case Outcome.Errored(ea) => F.raiseError(ea)
                case Outcome.Canceled() => F.productR(poll(F.canceled))(F.never)
              }
          }
      }
    }

    F.race(fa, F.never[B]) <-> results
  }

  def raceDerivesFromRacePairRight[A, B](fb: F[B]) = {
    val results: F[Either[A, B]] = F uncancelable { poll =>
      F.flatMap(F.racePair(F.never[A], fb)) {
        case Left((oc, f)) =>
          oc match {
            case Outcome.Succeeded(fa) => F.productR(f.cancel)(F.map(fa)(Left(_)))
            case Outcome.Errored(ea) => F.productR(f.cancel)(F.raiseError(ea))
            case Outcome.Canceled() =>
              F.flatMap(f.cancel *> f.join) {
                case Outcome.Succeeded(fb) => F.map(fb)(Right(_))
                case Outcome.Errored(eb) => F.raiseError(eb)
                case Outcome.Canceled() => F.productR(poll(F.canceled))(F.never)
              }
          }
        case Right((f, oc)) =>
          oc match {
            case Outcome.Succeeded(fb) => F.productR(f.cancel)(F.map(fb)(Right(_)))
            case Outcome.Errored(eb) => F.productR(f.cancel)(F.raiseError(eb))
            case Outcome.Canceled() =>
              F.flatMap(f.cancel *> f.join) {
                case Outcome.Succeeded(fa) => F.map(fa)(Left(_))
                case Outcome.Errored(ea) => F.raiseError(ea)
                case Outcome.Canceled() => F.productR(poll(F.canceled))(F.never)
              }
          }
      }
    }

    F.race(F.never[A], fb) <-> results
  }

  @deprecated("law is no longer applicable (or correct)", "3.5.0")
  def raceCanceledIdentityLeft[A](fa: F[A]) =
    F.race(F.canceled, fa.flatMap(F.pure(_)).handleErrorWith(F.raiseError(_))) <-> fa.map(
      _.asRight[Unit])

  @deprecated("law is no longer applicable (or correct)", "3.5.0")
  def raceCanceledIdentityRight[A](fa: F[A]) =
    F.race(fa.flatMap(F.pure(_)).handleErrorWith(F.raiseError(_)), F.canceled) <-> fa.map(
      _.asLeft[Unit])

  def raceNeverNoncanceledIdentityLeft[A](fa: F[A]) =
    F.race(F.never[Unit], fa.flatMap(F.pure(_)).handleErrorWith(F.raiseError(_))) <->
      fa.flatMap(r => F.pure(r.asRight[Unit])).handleErrorWith(F.raiseError(_))

  def raceNeverNoncanceledIdentityRight[A](fa: F[A]) =
    F.race(fa.flatMap(F.pure(_)).handleErrorWith(F.raiseError(_)), F.never[Unit]) <->
      fa.flatMap(r => F.pure(r.asLeft[Unit])).handleErrorWith(F.raiseError(_))

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

  def fiberJoinIsGuaranteeCase[A](fa0: F[A], f: Outcome[F, E, A] => F[Unit]) = {
    // the semantics of cancelation create boundary conditions we must avoid
    val fa = fa0.flatMap(F.pure(_)).handleErrorWith(F.raiseError(_))

    F.start(fa)
      .flatMap(_.join)
      .flatMap(oc => F.guarantee(oc.embed(F.canceled >> F.never[A]), f(oc))) <->
      F.guaranteeCase(fa)(f)
  }

  def neverDominatesOverFlatMap[A](fa: F[A]) =
    F.never >> fa <-> F.never[A]

  def uncancelableRaceNotInherited =
    F.uncancelable(_ => F.race(F.never[Unit], F.canceled)).void <-> F.never[Unit]

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
