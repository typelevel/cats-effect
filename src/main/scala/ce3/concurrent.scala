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

import cats.{MonadError, Traverse}

import scala.concurrent.duration.FiniteDuration

trait Fiber[F[_], E, A] {
  def cancel: F[Unit]
  def join: F[ExitCase[E, A]]
}

// fa.flatMap(a => if (a > 42) f.cancel else void)
//
// (f.cancel *> raiseError(new Exception)).attempt *> void

trait Concurrent[F[_], E] extends MonadError[F, E] { self: Safe[F, E] =>
  type Case[A] = ExitCase[E, A]

  final def CaseInstance: MonadError[ExitCase[E, ?], E] with Traverse[ExitCase[E, ?]] =
    ExitCase.instance[E]

  def start[A](fa: F[A]): F[Fiber[F, E, A]]

  // cancelable(uncancelable(fa)) <-> fa
  // uncancelable(cancelable(fa)) <-> fa
  // uncancelable(canceled *> fa) <-> uncancelable(fa)
  def cancelable[A](fa: F[A]): F[A]

  // uncancelable(fa) <-> bracket(fa)(_.pure)(_ => unit)
  def uncancelable[A](fa: F[A]): F[A] =
    bracket(fa)(_ => unit[F])(_ => unit[F])

  // produces an effect which is already canceled (and doesn't introduce an async boundary)
  def canceled[A]: F[A]

  // introduces a fairness boundary by yielding control to the underlying dispatcher
  def yielding: F[Unit]

  def racePair[A, B](fa: F[A], fb: F[B]): F[Either[(A, Fiber[F, E, B]), (Fiber[F, E, A], B)]]
}

object Concurrent {
  def apply[F[_], E](implicit F: Concurrent[F, E]): F.type = F
}
