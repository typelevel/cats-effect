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

import cats.{~>, Applicative, Functor}
import cats.data.{EitherT, IorT, Kleisli, OptionT, ReaderWriterStateT, StateT, WriterT}
import cats.effect.kernel.Sync
import cats.kernel.Monoid

trait SystemProperties[F[_]] { self =>

  /**
   * Gets the system property indicated by `key`.
   */
  def get(key: String): F[Option[String]]

  /**
   * Sets the system property indicated by `key`.
   */
  def set(key: String, value: String): F[Option[String]]

  /**
   * Removes the system property indicated by `key`.
   */
  def clear(key: String): F[Option[String]]

  def mapK[G[_]](f: F ~> G): SystemProperties[G] = new SystemProperties[G] {
    def get(key: String) = f(self.get(key))
    def set(key: String, value: String) = f(self.set(key, value))
    def clear(key: String) = f(self.clear(key))
  }
}

object SystemProperties {

  /**
   * Summoner method for `SystemProperties` instances.
   */
  def apply[F[_]](implicit ev: SystemProperties[F]): ev.type = ev

  /**
   * Constructs a `SystemProperties` instance for `F` data types that are
   * [[cats.effect.kernel.Sync]].
   */
  def make[F[_]](implicit F: Sync[F]): SystemProperties[F] = new SyncSystemProperties[F]

  /**
   * [[SystemProperties]] instance built for `cats.data.EitherT` values initialized with any `F`
   * data type that also implements [[SystemProperties]].
   */
  implicit def catsEitherTSystemProperties[F[_]: SystemProperties: Functor, L]
      : SystemProperties[EitherT[F, L, *]] =
    SystemProperties[F].mapK(EitherT.liftK)

  /**
   * [[SystemProperties]] instance built for `cats.data.Kleisli` values initialized with any `F`
   * data type that also implements [[SystemProperties]].
   */
  implicit def catsKleisliSystemProperties[F[_]: SystemProperties, R]
      : SystemProperties[Kleisli[F, R, *]] =
    SystemProperties[F].mapK(Kleisli.liftK)

  /**
   * [[SystemProperties]] instance built for `cats.data.OptionT` values initialized with any `F`
   * data type that also implements [[SystemProperties]].
   */
  implicit def catsOptionTSystemProperties[F[_]: SystemProperties: Functor]
      : SystemProperties[OptionT[F, *]] =
    SystemProperties[F].mapK(OptionT.liftK)

  /**
   * [[SystemProperties]] instance built for `cats.data.StateT` values initialized with any `F`
   * data type that also implements [[SystemProperties]].
   */
  implicit def catsStateTSystemProperties[F[_]: SystemProperties: Applicative, S]
      : SystemProperties[StateT[F, S, *]] =
    SystemProperties[F].mapK(StateT.liftK)

  /**
   * [[SystemProperties]] instance built for `cats.data.WriterT` values initialized with any `F`
   * data type that also implements [[SystemProperties]].
   */
  implicit def catsWriterTSystemProperties[
      F[_]: SystemProperties: Applicative,
      L: Monoid
  ]: SystemProperties[WriterT[F, L, *]] =
    SystemProperties[F].mapK(WriterT.liftK)

  /**
   * [[SystemProperties]] instance built for `cats.data.IorT` values initialized with any `F`
   * data type that also implements [[SystemProperties]].
   */
  implicit def catsIorTSystemProperties[F[_]: SystemProperties: Functor, L]
      : SystemProperties[IorT[F, L, *]] =
    SystemProperties[F].mapK(IorT.liftK)

  /**
   * [[SystemProperties]] instance built for `cats.data.ReaderWriterStateT` values initialized
   * with any `F` data type that also implements [[SystemProperties]].
   */
  implicit def catsReaderWriterStateTSystemProperties[
      F[_]: SystemProperties: Applicative,
      E,
      L: Monoid,
      S
  ]: SystemProperties[ReaderWriterStateT[F, E, L, S, *]] =
    SystemProperties[F].mapK(ReaderWriterStateT.liftK)

  private[std] final class SyncSystemProperties[F[_]](implicit F: Sync[F])
      extends SystemProperties[F] {

    def get(key: String) = F.delay(Option(System.getProperty(key)))

    def set(key: String, value: String) = F.blocking(Option(System.setProperty(key, value)))

    def clear(key: String) = F.blocking(Option(System.clearProperty(key)))
  }
}
