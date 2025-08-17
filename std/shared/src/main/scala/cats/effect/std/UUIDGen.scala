/*
 * Copyright 2020-2025 Typelevel
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

package cats.effect.std

import cats.{~>, Applicative, Functor, Monoid}
import cats.data.{
  EitherT,
  IndexedReaderWriterStateT,
  IndexedStateT,
  IorT,
  Kleisli,
  OptionT,
  WriterT
}
import cats.implicits._

import java.util.UUID

/**
 * A purely functional UUID Generator
 */
trait UUIDGen[F[_]] { self =>

  /**
   * Generates a UUID in a pseudorandom manner.
   * @return
   *   randomly generated UUID
   */
  def randomUUID: F[UUID]

  /**
   * Modifies the context in which this [[UUIDGen]] operates using the natural transformation
   * `f`.
   *
   * @return
   *   a [[UUIDGen]] in the new context obtained by mapping the current one using `f`
   */
  def mapK[G[_]](f: F ~> G): UUIDGen[G] =
    new UUIDGen.TranslatedUUIDGen[F, G](self)(f)
}

object UUIDGen extends UUIDGenCompanionPlatform {
  def apply[F[_]](implicit ev: UUIDGen[F]): UUIDGen[F] = ev

  def randomUUID[F[_]: UUIDGen]: F[UUID] = UUIDGen[F].randomUUID
  def randomString[F[_]](implicit gen: UUIDGen[F], F: Functor[F]): F[String] =
    randomUUID(using gen).map(_.toString)

  /**
   * [[UUIDGen]] instance built for `cats.data.EitherT` values initialized with any `F` data
   * type that also implements `UUIDGen`.
   */
  implicit def catsEitherTUUIDGen[F[_]: UUIDGen: Functor, L]: UUIDGen[EitherT[F, L, *]] =
    UUIDGen[F].mapK(EitherT.liftK)

  /**
   * [[UUIDGen]] instance built for `cats.data.Kleisli` values initialized with any `F` data
   * type that also implements `UUIDGen`.
   */
  implicit def catsKleisliUUIDGen[F[_]: UUIDGen, R]: UUIDGen[Kleisli[F, R, *]] =
    UUIDGen[F].mapK(Kleisli.liftK)

  /**
   * [[UUIDGen]] instance built for `cats.data.OptionT` values initialized with any `F` data
   * type that also implements `UUIDGen`.
   */
  implicit def catsOptionTUUIDGen[F[_]: UUIDGen: Functor]: UUIDGen[OptionT[F, *]] =
    UUIDGen[F].mapK(OptionT.liftK)

  /**
   * [[UUIDGen]] instance built for `cats.data.IndexedStateT` values initialized with any `F`
   * data type that also implements `UUIDGen`.
   */
  implicit def catsIndexedStateTUUIDGen[F[_]: UUIDGen: Applicative, S]
      : UUIDGen[IndexedStateT[F, S, S, *]] =
    UUIDGen[F].mapK(IndexedStateT.liftK)

  /**
   * [[UUIDGen]] instance built for `cats.data.WriterT` values initialized with any `F` data
   * type that also implements `UUIDGen`.
   */
  implicit def catsWriterTUUIDGen[
      F[_]: UUIDGen: Applicative,
      L: Monoid
  ]: UUIDGen[WriterT[F, L, *]] =
    UUIDGen[F].mapK(WriterT.liftK)

  /**
   * [[UUIDGen]] instance built for `cats.data.IorT` values initialized with any `F` data type
   * that also implements `UUIDGen`.
   */
  implicit def catsIorTUUIDGen[F[_]: UUIDGen: Functor, L]: UUIDGen[IorT[F, L, *]] =
    UUIDGen[F].mapK(IorT.liftK)

  /**
   * [[UUIDGen]] instance built for `cats.data.IndexedReaderWriterStateT` values initialized
   * with any `F` data type that also implements `UUIDGen`.
   */
  implicit def catsIndexedReaderWriterStateTUUIDGen[
      F[_]: UUIDGen: Applicative,
      E,
      L: Monoid,
      S
  ]: UUIDGen[IndexedReaderWriterStateT[F, E, L, S, S, *]] =
    UUIDGen[F].mapK(IndexedReaderWriterStateT.liftK)

  implicit def fromSecureRandom[F[_]: Functor: SecureRandom]: UUIDGen[F] =
    new UUIDGen[F] {
      override final val randomUUID: F[UUID] =
        SecureRandom[F].nextBytes(16).map(unsafeUUIDBuilder)

      private def unsafeUUIDBuilder(buffer: Array[Byte]): UUID = {
        @inline def intFromBuffer(i: Int): Int =
          (buffer(i) << 24) | ((buffer(i + 1) & 0xff) << 16) | ((buffer(
            i + 2) & 0xff) << 8) | (buffer(i + 3) & 0xff)

        val i1 = intFromBuffer(0)
        val i2 = (intFromBuffer(4) & ~0x0000f000) | 0x00004000
        val i3 = (intFromBuffer(8) & ~0xc0000000) | 0x80000000
        val i4 = intFromBuffer(12)
        val msb = (i1.toLong << 32) | (i2.toLong & 0xffffffffL)
        val lsb = (i3.toLong << 32) | (i4.toLong & 0xffffffffL)
        new UUID(msb, lsb)
      }
    }

  private[std] final class TranslatedUUIDGen[F[_], G[_]](self: UUIDGen[F])(f: F ~> G)
      extends UUIDGen[G] {
    override def randomUUID: G[UUID] =
      f(self.randomUUID)

  }
}
