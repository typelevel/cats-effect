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

import cats.{~>, ApplicativeError, MonadError, Traverse}

import scala.concurrent.duration.FiniteDuration

trait Fiber[F[_], E, A] {
  def cancel: F[Unit]
  def join: F[ExitCase[F, E, A]]
}

// fa.flatMap(a => if (a > 42) f.cancel else unit)
//
// (f.cancel *> raiseError(new Exception)).attempt *> unit

trait Concurrent[F[_], E] extends MonadError[F, E] { self: Safe[F, E] =>
  type Case[A] = ExitCase[F, E, A]

  final def CaseInstance: ApplicativeError[ExitCase[F, E, ?], E] =
    ExitCase.applicativeError[F, E](this)

  def start[A](fa: F[A]): F[Fiber[F, E, A]]

  // uncancelable(_(fa)) <-> fa
  // uncanceable(_ => fa).start.flatMap(f => f.cancel >> f.join) <-> fa.map(ExitCase.Completed(_))
  def uncancelable[A](body: (F ~> F) => F[A]): F[A]

  // produces an effect which is already canceled (and doesn't introduce an async boundary)
  // this is effectively a way for a fiber to commit suicide and yield back to its parent
  // The fallback value is produced if the effect is sequenced into a block in which
  // cancelation is suppressed.
  //
  // uncancelable(_ => canceled(a)) <-> pure(a)
  // (canceled(a) >> never).start.void <-> pure(a).start.flatMap(_.cancel)
  def canceled[A](fallback: A): F[A]

  // produces an effect which never returns
  def never[A]: F[A]

  // introduces a fairness boundary by yielding control to the underlying dispatcher
  def cede: F[Unit]

  def racePair[A, B](fa: F[A], fb: F[B]): F[Either[(A, Fiber[F, E, B]), (Fiber[F, E, A], B)]]
}

object Concurrent {
  def apply[F[_], E](implicit F: Concurrent[F, E]): F.type = F
}
