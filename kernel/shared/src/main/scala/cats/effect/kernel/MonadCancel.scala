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

package cats.effect.kernel

import cats.{~>, MonadError}

trait MonadCancel[F[_], E] extends MonadError[F, E] {

  // analogous to productR, except discarding short-circuiting (and optionally some effect contexts) except for cancelation
  def forceR[A, B](fa: F[A])(fb: F[B]): F[B]

  def uncancelable[A](body: (F ~> F) => F[A]): F[A]

  // produces an effect which is already canceled (and doesn't introduce an async boundary)
  // this is effectively a way for a fiber to commit suicide and yield back to its parent
  // The fallback (unit) value is produced if the effect is sequenced into a block in which
  // cancelation is suppressed.
  def canceled: F[Unit]

  def onCancel[A](fa: F[A], fin: F[Unit]): F[A]

  def guarantee[A](fa: F[A], fin: F[Unit]): F[A] =
    guaranteeCase(fa)(_ => fin)

  def guaranteeCase[A](fa: F[A])(fin: Outcome[F, E, A] => F[Unit]): F[A] =
    bracketCase(unit)(_ => fa)((_, oc) => fin(oc))

  def bracket[A, B](acquire: F[A])(use: A => F[B])(release: A => F[Unit]): F[B] =
    bracketCase(acquire)(use)((a, _) => release(a))

  def bracketCase[A, B](acquire: F[A])(use: A => F[B])(
      release: (A, Outcome[F, E, B]) => F[Unit]): F[B] =
    bracketFull(_ => acquire)(use)(release)

  def bracketFull[A, B](acquire: (F ~> F) => F[A])(use: A => F[B])(
      release: (A, Outcome[F, E, B]) => F[Unit]): F[B] =
    uncancelable { poll =>
      flatMap(acquire(poll)) { a =>
        val finalized = onCancel(poll(use(a)), release(a, Outcome.Canceled()))
        val handled = onError(finalized) {
          case e => void(attempt(release(a, Outcome.Errored(e))))
        }
        flatMap(handled)(b => as(attempt(release(a, Outcome.Completed(pure(b)))), b))
      }
    }
}

object MonadCancel {

  def apply[F[_], E](implicit F: MonadCancel[F, E]): F.type = F

  def apply[F[_]](implicit F: MonadCancel[F, _], d: DummyImplicit): F.type = F

}
