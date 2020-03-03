/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

package cats
package effect

import simulacrum._
import cats.data.{EitherT, WriterT}

/**
 * A monad that can suspend side effects into the `F` context and
 * that supports only synchronous lazy evaluation of these effects.
 */
@typeclass
trait SyncEffect[F[_]] extends Sync[F] {

  /**
   * Convert to any other type that implements `Sync`.
   */
  def runSync[G[_], A](fa: F[A])(implicit G: Sync[G]): G[A]

  /**
   * [[SyncEffect.runSync]] as a natural transformation.
   */
  def runSyncK[G[_]](implicit G: Sync[G]): F ~> G = new (F ~> G) {
    def apply[A](fa: F[A]): G[A] = runSync[G, A](fa)
  }
}

object SyncEffect {

  /**
   * [[SyncEffect]] instance built for `cats.data.EitherT` values initialized
   * with any `F` data type that also implements `SyncEffect`.
   */
  implicit def catsEitherTSyncEffect[F[_]: SyncEffect]: SyncEffect[EitherT[F, Throwable, *]] =
    new EitherTSyncEffect[F] { def F = SyncEffect[F] }

  /**
   * [[SyncEffect]] instance built for `cats.data.WriterT` values initialized
   * with any `F` data type that also implements `SyncEffect`.
   */
  implicit def catsWriterTSyncEffect[F[_]: SyncEffect, L: Monoid]: SyncEffect[WriterT[F, L, *]] =
    new WriterTSyncEffect[F, L] { def F = SyncEffect[F]; def L = Monoid[L] }

  private[effect] trait EitherTSyncEffect[F[_]]
      extends SyncEffect[EitherT[F, Throwable, *]]
      with Sync.EitherTSync[F, Throwable] {
    protected def F: SyncEffect[F]

    def runSync[G[_], A](fa: EitherT[F, Throwable, A])(implicit G: Sync[G]): G[A] =
      F.runSync(F.rethrow(fa.value))
  }

  private[effect] trait WriterTSyncEffect[F[_], L] extends SyncEffect[WriterT[F, L, *]] with Sync.WriterTSync[F, L] {
    protected def F: SyncEffect[F]
    protected def L: Monoid[L]

    def runSync[G[_], A](fa: WriterT[F, L, A])(implicit G: Sync[G]): G[A] =
      F.runSync(F.map(fa.run)(_._2))
  }
}
