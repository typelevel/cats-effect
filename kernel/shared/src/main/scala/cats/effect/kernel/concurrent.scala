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
import cats.syntax.all._

trait Fiber[F[_], E, A] {
  def cancel: F[Unit]
  def join: F[Outcome[F, E, A]]

  def joinAndEmbed(onCancel: F[A])(implicit F: Concurrent[F, E]): F[A] =
    join.flatMap(_.fold(onCancel, F.raiseError(_), fa => fa))

  def joinAndEmbedNever(implicit F: Concurrent[F, E]): F[A] =
    joinAndEmbed(F.never)
}

trait Concurrent[F[_], E] extends MonadError[F, E] {

  def start[A](fa: F[A]): F[Fiber[F, E, A]]

  def uncancelable[A](body: (F ~> F) => F[A]): F[A]

  // produces an effect which is already canceled (and doesn't introduce an async boundary)
  // this is effectively a way for a fiber to commit suicide and yield back to its parent
  // The fallback (unit) value is produced if the effect is sequenced into a block in which
  // cancelation is suppressed.
  def canceled: F[Unit]

  def onCancel[A](fa: F[A], fin: F[Unit]): F[A]

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

  // produces an effect which never returns
  def never[A]: F[A]

  // introduces a fairness boundary by yielding control to the underlying dispatcher
  def cede: F[Unit]

  def racePair[A, B](fa: F[A], fb: F[B]): F[Either[(A, Fiber[F, E, B]), (Fiber[F, E, A], B)]]

  def race[A, B](fa: F[A], fb: F[B]): F[Either[A, B]] =
    flatMap(racePair(fa, fb)) {
      case Left((a, f)) => as(f.cancel, a.asLeft[B])
      case Right((f, b)) => as(f.cancel, b.asRight[A])
    }

  def both[A, B](fa: F[A], fb: F[B]): F[(A, B)] =
    flatMap(racePair(fa, fb)) {
      case Left((a, f)) =>
        flatMap(f.join) { c =>
          c.fold(
            flatMap(canceled)(_ =>
              never), // if our child canceled, then we must also be cancelable since racePair forwards our masks along, so it's safe to use never
            e => raiseError[(A, B)](e),
            tupleLeft(_, a)
          )
        }

      case Right((f, b)) =>
        flatMap(f.join) { c =>
          c.fold(flatMap(canceled)(_ => never), e => raiseError[(A, B)](e), tupleRight(_, b))
        }
    }
}

object Concurrent {
  def apply[F[_], E](implicit F: Concurrent[F, E]): F.type = F
  def apply[F[_]](implicit F: Concurrent[F, _], d: DummyImplicit): F.type = F
}
