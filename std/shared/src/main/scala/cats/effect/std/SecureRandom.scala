/*
 * Copyright 2020-2022 Typelevel
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
package std

import cats._
import cats.data.{
  EitherT,
  IndexedReaderWriterStateT,
  IndexedStateT,
  IorT,
  Kleisli,
  OptionT,
  WriterT
}
import cats.effect.kernel._
import cats.syntax.all._

import scala.util.{Random => SRandom}

/**
 * SecureRandom is the ability to get cryptographically strong random
 * information.  It is an extension of the Random interface, but is
 * used where weaker implementations must be precluded.
 */
trait SecureRandom[F[_]] extends Random[F] { self =>
  override def mapK[G[_]](f: F ~> G): SecureRandom[G] =
    new Random.TranslatedRandom[F, G](self)(f) with SecureRandom[G]
}

object SecureRandom {

  def apply[F[_]](implicit ev: SecureRandom[F]): SecureRandom[F] = ev

  /**
   * [[SecureRandom]] instance built for `cats.data.EitherT` values initialized with any `F` data type
   * that also implements `SecureRandom`.
   */
  implicit def catsEitherTRandom[F[_]: SecureRandom: Functor, L]: SecureRandom[EitherT[F, L, *]] =
    SecureRandom[F].mapK(EitherT.liftK)

  /**
   * [[SecureRandom]] instance built for `cats.data.Kleisli` values initialized with any `F` data type
   * that also implements `SecureRandom`.
   */
  implicit def catsKleisliSecureRandom[F[_]: SecureRandom, R]: SecureRandom[Kleisli[F, R, *]] =
    SecureRandom[F].mapK(Kleisli.liftK)

  /**
   * [[SecureRandom]] instance built for `cats.data.OptionT` values initialized with any `F` data type
   * that also implements `SecureRandom`.
   */
  implicit def catsOptionTSecureRandom[F[_]: SecureRandom: Functor]: SecureRandom[OptionT[F, *]] =
    SecureRandom[F].mapK(OptionT.liftK)

  /**
   * [[SecureRandom]] instance built for `cats.data.IndexedStateT` values initialized with any `F`
   * data type that also implements `SecureRandom`.
   */
  implicit def catsIndexedStateTSecureRandom[F[_]: SecureRandom: Applicative, S]
      : SecureRandom[IndexedStateT[F, S, S, *]] =
    SecureRandom[F].mapK(IndexedStateT.liftK)

  /**
   * [[SecureRandom]] instance built for `cats.data.WriterT` values initialized with any `F` data type
   * that also implements `SecureRandom`.
   */
  implicit def catsWriterTSecureRandom[
      F[_]: SecureRandom: Applicative,
      L: Monoid
  ]: SecureRandom[WriterT[F, L, *]] =
    SecureRandom[F].mapK(WriterT.liftK)

  /**
   * [[SecureRandom]] instance built for `cats.data.IorT` values initialized with any `F` data type
   * that also implements `SecureRandom`.
   */
  implicit def catsIorTSecureRandom[F[_]: SecureRandom: Functor, L]: SecureRandom[IorT[F, L, *]] =
    SecureRandom[F].mapK(IorT.liftK)

  /**
   * [[SecureRandom]] instance built for `cats.data.IndexedReaderWriterStateT` values initialized with
   * any `F` data type that also implements `SecureRandom`.
   */
  implicit def catsIndexedReaderWriterStateTSecureRandom[
      F[_]: SecureRandom: Applicative,
      E,
      L: Monoid,
      S
  ]: SecureRandom[IndexedReaderWriterStateT[F, E, L, S, S, *]] =
    SecureRandom[F].mapK(IndexedReaderWriterStateT.liftK)

  def javaSecuritySecureRandom[F[_]: Sync](n: Int): F[SecureRandom[F]] =
    for {
      ref <- Ref[F].of(0)
      array <- Sync[F].delay(Array.fill(n)(new SRandom(new java.security.SecureRandom)))
    } yield {
      def incrGet = ref.modify(i => (if (i < (n - 1)) i + 1 else 0, i))
      def selectRandom = incrGet.map(array(_))
      new Random.ScalaRandom[F](selectRandom) with SecureRandom[F] {}
    }

  def javaSecuritySecureRandom[F[_]: Sync]: F[SecureRandom[F]] =
    Sync[F].delay(new java.security.SecureRandom).map(r =>
      new Random.ScalaRandom[F](Applicative[F].pure(r)) with SecureRandom[F] {})
}
