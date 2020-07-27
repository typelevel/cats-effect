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

import cats.implicits._
import cats.{~>, Monoid}
import cats.data.{EitherT, WriterT}

trait SyncEffect[F[_]] extends Sync[F] {

  def to[G[_]]: PartiallyApplied[G] =
    new PartiallyApplied[G]

  def toK[G[_]: SyncEffect]: F ~> G

  final class PartiallyApplied[G[_]] {
    def apply[A](fa: F[A])(implicit G: SyncEffect[G]): G[A] =
      toK[G](G)(fa)
  }
}

object SyncEffect {
  def apply[F[_]](implicit F: SyncEffect[F]): F.type = F

  implicit def syncEffectForEitherT[F[_]](
      implicit F0: SyncEffect[F]): SyncEffect[EitherT[F, Throwable, *]] =
    new EitherTSyncEffect[F] {
      implicit override def F: SyncEffect[F] = F0
    }

  implicit def syncEffectForWriterT[F[_], S](
      implicit F0: SyncEffect[F],
      S0: Monoid[S]): SyncEffect[WriterT[F, S, *]] =
    new WriterTSyncEffect[F, S] {
      implicit override def F: SyncEffect[F] = F0

      implicit override def S: Monoid[S] = S0
    }

  trait EitherTSyncEffect[F[_]]
      extends SyncEffect[EitherT[F, Throwable, *]]
      with Sync.EitherTSync[F, Throwable] {
    implicit protected def F: SyncEffect[F]

    def toK[G[_]: SyncEffect]: EitherT[F, Throwable, *] ~> G =
      new ~>[EitherT[F, Throwable, *], G] {
        def apply[A](a: EitherT[F, Throwable, A]): G[A] = F.to[G](a.value).rethrow
      }

  }

  trait WriterTSyncEffect[F[_], S]
      extends SyncEffect[WriterT[F, S, *]]
      with Sync.WriterTSync[F, S] {

    implicit protected def F: SyncEffect[F]

    def toK[G[_]: SyncEffect]: WriterT[F, S, *] ~> G =
      new ~>[WriterT[F, S, *], G] {
        def apply[A](a: WriterT[F, S, A]): G[A] = F.to[G](F.map(a.run)(_._2))
      }

  }
}
