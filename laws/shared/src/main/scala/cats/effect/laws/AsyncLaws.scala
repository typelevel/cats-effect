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

import cats.implicits._

import scala.concurrent.ExecutionContext
import scala.util.{Left, Right}

trait AsyncLaws[F[_]] extends TemporalLaws[F, Throwable] with SyncLaws[F] {
  implicit val F: Async[F]

  def asyncRightIsPure[A](a: A) =
    F.async[A](k => F.delay(k(Right(a))).as(None)) <-> F.pure(a)

  def asyncLeftIsRaiseError[A](e: Throwable) =
    F.async[A](k => F.delay(k(Left(e))).as(None)) <-> F.raiseError(e)

  def asyncRepeatedCallbackIgnored[A](a: A) =
    F.async[A](k => F.delay(k(Right(a))) >> F.delay(k(Right(a))).as(None)) <-> F.pure(a)

  def asyncCancelTokenIsUnsequencedOnCompletion[A](a: A, fu: F[Unit]) =
    F.async[A](k => F.delay(k(Right(a))) >> F.pure(Some(fu))) <-> F.pure(a)

  def asyncCancelTokenIsUnsequencedOnError[A](e: Throwable, fu: F[Unit]) =
    F.async[A](k => F.delay(k(Left(e))) >> F.pure(Some(fu))) <-> F.raiseError(e)

  def asyncCancelTokenIsSequencedOnCancel(fu: F[Unit]) =
    F.start(F.async[Unit](_ => F.pure(Some(fu)))).flatMap(_.cancel) <-> fu.attempt.void

  def neverIsDerivedFromAsync[A] =
    F.never[A] <-> F.async[A](_ => F.pure(None))

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
}

object AsyncLaws {
  def apply[F[_]](implicit F0: Async[F]): AsyncLaws[F] =
    new AsyncLaws[F] { val F = F0 }
}
