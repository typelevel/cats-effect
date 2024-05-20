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

import cats.effect.kernel.Async
import cats.syntax.all._

import scala.concurrent.ExecutionContext
import scala.util.{Left, Right}

trait AsyncLaws[F[_]] extends GenTemporalLaws[F, Throwable] with SyncLaws[F] {
  implicit val F: Async[F]

  // format: off
  def asyncCheckAttemptImmediateIsPure[A](a: A) =
    (F.asyncCheckAttempt[A](_ => F.pure(Right(a))) <* F.unit) <-> (F.pure(a))
  // format: on

  // format: off
  def asyncCheckAttemptSuspendedRightIsAsyncRight[A](a: A, fu: F[Unit]) =
    (F.asyncCheckAttempt[A](k => F.delay(k(Right(a))) >> fu.as(Left(None))) <* F.unit) <-> (F.async[A](k => F.delay(k(Right(a))) >> fu.as(None)) <* F.unit)
  // format: on

  // format: off
  def asyncCheckAttemptSuspendedLeftIsAsyncLeft[A](e: Throwable, fu: F[Unit]) =
    (F.asyncCheckAttempt[A](k => F.delay(k(Left(e))) >> fu.as(Left(None))) <* F.unit) <-> (F.async[A](k => F.delay(k(Left(e))) >> fu.as(None)) <* F.unit)
  // format: on

  // format: off
  def asyncRightIsUncancelableSequencedPure[A](a: A, fu: F[Unit]) =
    (F.async[A](k => F.delay(k(Right(a))) >> fu.as(None)) <* F.unit) <-> (F.uncancelable(_ => fu) >> F.pure(a))
  // format: on

  // format: off
  def asyncLeftIsUncancelableSequencedRaiseError[A](e: Throwable, fu: F[Unit]) =
    (F.async[A](k => F.delay(k(Left(e))) >> fu.as(None)) <* F.unit) <-> (F.uncancelable(_ => fu) >> F.raiseError(e))
  // format: on

  def asyncRepeatedCallbackIgnored[A](a: A) =
    F.async[A](k => F.delay(k(Right(a))) >> F.delay(k(Right(a))).as(None)) <-> F.pure(a)

  def asyncCancelTokenIsUnsequencedOnCompletion[A](a: A, fu: F[Unit]) =
    F.async[A](k => F.delay(k(Right(a))) >> F.pure(Some(fu))) <-> F.pure(a)

  def asyncCancelTokenIsUnsequencedOnError[A](e: Throwable, fu: F[Unit]) =
    F.async[A](k => F.delay(k(Left(e))) >> F.pure(Some(fu))) <-> F.raiseError(e)

  def neverIsDerivedFromAsync[A] =
    F.never[A] <-> F.async[A](_ => F.pure(Some(F.unit)))

  def executionContextCommutativity[A](fa: F[A]) =
    (fa *> F.executionContext) <-> (F.executionContext <* fa)

  def evalOnLocalPure(ec: ExecutionContext) =
    F.evalOn(F.executionContext, ec) <-> F.evalOn(F.pure(ec), ec)

  def evalOnPureIdentity[A](a: A, ec: ExecutionContext) =
    F.evalOn(F.pure(a), ec) <-> F.pure(a)

  def evalOnRaiseErrorIdentity(e: Throwable, ec: ExecutionContext) =
    F.evalOn(F.raiseError[Unit](e), ec) <-> F.raiseError[Unit](e)

  def evalOnCanceledIdentity(ec: ExecutionContext) =
    F.evalOn(F.canceled, ec) <-> F.canceled

  def evalOnNeverIdentity(ec: ExecutionContext) =
    F.evalOn(F.never[Unit], ec) <-> F.never[Unit]

  def syncStepIdentity[A](fa: F[A], limit: Int) =
    F.syncStep(fa, limit).flatMap {
      case Left(fa) => fa
      case Right(a) => F.pure(a)
    } <-> fa
}

object AsyncLaws {
  def apply[F[_]](implicit F0: Async[F]): AsyncLaws[F] =
    new AsyncLaws[F] { val F = F0 }
}
