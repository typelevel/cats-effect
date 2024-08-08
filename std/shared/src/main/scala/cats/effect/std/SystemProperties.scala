/*
 * Copyright 2020-2024 Typelevel
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

import org.typelevel.scalaccompat.annotation._

import scala.collection.immutable.Map

trait SystemProperties[F[_]] { self =>

  /**
   * Retrieves the value for the specified key.
   */
  def get(key: String): F[Option[String]]

  /**
   * Sets the value for the specified key to `value` disregarding any previous value for the
   * same key.
   */
  def set(key: String, value: String): F[Unit]

  /**
   * Removes the property.
   */
  def unset(key: String): F[Unit]

  def entries: F[Map[String, String]]

  def mapK[G[_]](f: F ~> G): SystemProperties[G] = new SystemProperties[G] {
    def get(key: String): G[Option[String]] = f(self.get(key))
    def set(key: String, value: String): G[Unit] = f(self.set(key, value))
    def unset(key: String) = f(self.unset(key))
    def entries: G[Map[String, String]] = f(self.entries)
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
   * [[Prop]] instance built for `cats.data.EitherT` values initialized with any `F` data type
   * that also implements `Prop`.
   */
  implicit def catsEitherTSystemProperties[F[_]: SystemProperties: Functor, L]
      : SystemProperties[EitherT[F, L, *]] =
    SystemProperties[F].mapK(EitherT.liftK)

  /**
   * [[Prop]] instance built for `cats.data.Kleisli` values initialized with any `F` data type
   * that also implements `Prop`.
   */
  implicit def catsKleisliSystemProperties[F[_]: SystemProperties, R]
      : SystemProperties[Kleisli[F, R, *]] =
    SystemProperties[F].mapK(Kleisli.liftK)

  /**
   * [[Prop]] instance built for `cats.data.OptionT` values initialized with any `F` data type
   * that also implements `Prop`.
   */
  implicit def catsOptionTSystemProperties[F[_]: SystemProperties: Functor]
      : SystemProperties[OptionT[F, *]] =
    SystemProperties[F].mapK(OptionT.liftK)

  /**
   * [[Prop]] instance built for `cats.data.StateT` values initialized with any `F` data type
   * that also implements `Prop`.
   */
  implicit def catsStateTSystemProperties[F[_]: SystemProperties: Applicative, S]
      : SystemProperties[StateT[F, S, *]] =
    SystemProperties[F].mapK(StateT.liftK)

  /**
   * [[Prop]] instance built for `cats.data.WriterT` values initialized with any `F` data type
   * that also implements `Prop`.
   */
  implicit def catsWriterTSystemProperties[
      F[_]: SystemProperties: Applicative,
      L: Monoid
  ]: SystemProperties[WriterT[F, L, *]] =
    SystemProperties[F].mapK(WriterT.liftK)

  /**
   * [[Prop]] instance built for `cats.data.IorT` values initialized with any `F` data type that
   * also implements `Prop`.
   */
  implicit def catsIorTSystemProperties[F[_]: SystemProperties: Functor, L]
      : SystemProperties[IorT[F, L, *]] =
    SystemProperties[F].mapK(IorT.liftK)

  /**
   * [[Prop]] instance built for `cats.data.ReaderWriterStateT` values initialized with any `F`
   * data type that also implements `Prop`.
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

    def get(key: String): F[Option[String]] =
      F.delay(Option(System.getProperty(key))) // thread-safe

    def set(key: String, value: String): F[Unit] =
      F.void(F.blocking(System.setProperty(key, value)))

    def unset(key: String): F[Unit] = F.void(F.delay(System.clearProperty(key)))

    @nowarn213("cat=deprecation")
    @nowarn3("cat=deprecation")
    def entries: F[Map[String, String]] =
      F.blocking {
        import scala.collection.JavaConverters._
        val props = System.getProperties
        val back = props.clone().asInstanceOf[java.util.Map[String, String]]
        Map.empty ++ back.asScala
      }
  }
}
