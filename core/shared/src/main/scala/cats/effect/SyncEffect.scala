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

import cats.data.{EitherT, WriterT}
import scala.annotation.implicitNotFound

/**
 * A monad that can suspend side effects into the `F` context and
 * that supports only synchronous lazy evaluation of these effects.
 */
@implicitNotFound("Could not find an instance of SyncEffect for ${F}")
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

  /****************************************************************************/
  /* THE FOLLOWING CODE IS MANAGED BY SIMULACRUM; PLEASE DO NOT EDIT!!!!      */
  /****************************************************************************/
  /**
   * Summon an instance of [[SyncEffect]] for `F`.
   */
  @inline def apply[F[_]](implicit instance: SyncEffect[F]): SyncEffect[F] = instance

  trait Ops[F[_], A] {
    type TypeClassType <: SyncEffect[F]
    def self: F[A]
    val typeClassInstance: TypeClassType
    def runSync[G[_]](implicit G: Sync[G]): G[A] = typeClassInstance.runSync[G, A](self)(G)
  }
  trait AllOps[F[_], A] extends Ops[F, A] with Sync.AllOps[F, A] {
    type TypeClassType <: SyncEffect[F]
  }
  trait ToSyncEffectOps {
    implicit def toSyncEffectOps[F[_], A](target: F[A])(implicit tc: SyncEffect[F]): Ops[F, A] {
      type TypeClassType = SyncEffect[F]
    } = new Ops[F, A] {
      type TypeClassType = SyncEffect[F]
      val self: F[A] = target
      val typeClassInstance: TypeClassType = tc
    }
  }
  object nonInheritedOps extends ToSyncEffectOps
  object ops {
    implicit def toAllSyncEffectOps[F[_], A](target: F[A])(implicit tc: SyncEffect[F]): AllOps[F, A] {
      type TypeClassType = SyncEffect[F]
    } = new AllOps[F, A] {
      type TypeClassType = SyncEffect[F]
      val self: F[A] = target
      val typeClassInstance: TypeClassType = tc
    }
  }

  /****************************************************************************/
  /* END OF SIMULACRUM-MANAGED CODE                                           */
  /****************************************************************************/

}
