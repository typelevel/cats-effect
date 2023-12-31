/*
 * Copyright 2020-2023 Typelevel
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

trait Prop[F[_]] { self =>

  /**
   * Retrieves the value for the specified key.
   */
  def get(key: String): F[Option[String]]

  /**
   * Replaces the value for the specified key with `value`, returning the previous value.
   */
  def getAndSet(key: String, value: String): F[Option[String]] = getAndUpdate(key, _ => value)

  /**
   * Updates the value for the specified key using `f` if a previous value for the same key
   * exists and returns the previous value.
   */
  def getAndUpdate(key: String, f: String => String): F[Option[String]] =
    modify(key, a => (f(a), a))

  /**
   * Modifies the value for the specified key only if a previous value for the same key exists.
   */
  def modify(key: String, f: String => (String, String)): F[Option[String]]

  /**
   * Sets the value for the specified key to `value` disregarding any previous value for the
   * same key.
   */
  def set(key: String, value: String): F[Unit]

  /**
   * Satisfies: `prop.update(key, f) == prop.modify(key, x => (f(x), a)).void` and
   * `prop.update(_ => a) == prop.set(a)`
   */
  def update(key: String, f: String => String): F[Unit]

  /**
   * Updates the value for the specified key using `f` if a previous value for the same key
   * exists and returns the updated value.
   */
  def updateAndGet(key: String, f: String => String): F[Option[String]] =
    modify(
      key,
      a => {
        val newA = f(a)
        (newA, newA)
      })

  /**
   * Removes the property.
   */
  def unset(key: String): F[Unit]

  def mapK[G[_]](f: F ~> G): Prop[G] = new Prop[G] {
    def get(key: String): G[Option[String]] = f(self.get(key))
    def modify(key: String, fn: String => (String, String)): G[Option[String]] = f(
      self.modify(key, fn))
    def set(key: String, value: String): G[Unit] = f(self.set(key, value))
    def update(key: String, fn: String => String): G[Unit] = f(self.update(key, fn))
    def unset(key: String) = f(self.unset(key))
  }
}

object Prop {

  /**
   * Summoner method for `Prop` instances.
   */
  def apply[F[_]](implicit ev: Prop[F]): ev.type = ev

  /**
   * Constructs a `Prop` instance for `F` data types that are [[cats.effect.kernel.Sync]].
   */
  def make[F[_]](implicit F: Sync[F]): Prop[F] = new SyncProp[F]

  /**
   * [[Prop]] instance built for `cats.data.EitherT` values initialized with any `F` data type
   * that also implements `Prop`.
   */
  implicit def catsEitherTProp[F[_]: Prop: Functor, L]: Prop[EitherT[F, L, *]] =
    Prop[F].mapK(EitherT.liftK)

  /**
   * [[Prop]] instance built for `cats.data.Kleisli` values initialized with any `F` data type
   * that also implements `Prop`.
   */
  implicit def catsKleisliProp[F[_]: Prop, R]: Prop[Kleisli[F, R, *]] =
    Prop[F].mapK(Kleisli.liftK)

  /**
   * [[Prop]] instance built for `cats.data.OptionT` values initialized with any `F` data type
   * that also implements `Prop`.
   */
  implicit def catsOptionTProp[F[_]: Prop: Functor]: Prop[OptionT[F, *]] =
    Prop[F].mapK(OptionT.liftK)

  /**
   * [[Prop]] instance built for `cats.data.StateT` values initialized with any `F` data type
   * that also implements `Prop`.
   */
  implicit def catsStateTProp[F[_]: Prop: Applicative, S]: Prop[StateT[F, S, *]] =
    Prop[F].mapK(StateT.liftK)

  /**
   * [[Prop]] instance built for `cats.data.WriterT` values initialized with any `F` data type
   * that also implements `Prop`.
   */
  implicit def catsWriterTProp[
      F[_]: Prop: Applicative,
      L: Monoid
  ]: Prop[WriterT[F, L, *]] =
    Prop[F].mapK(WriterT.liftK)

  /**
   * [[Prop]] instance built for `cats.data.IorT` values initialized with any `F` data type that
   * also implements `Prop`.
   */
  implicit def catsIorTProp[F[_]: Prop: Functor, L]: Prop[IorT[F, L, *]] =
    Prop[F].mapK(IorT.liftK)

  /**
   * [[Prop]] instance built for `cats.data.ReaderWriterStateT` values initialized with any `F`
   * data type that also implements `Prop`.
   */
  implicit def catsReaderWriterStateTProp[
      F[_]: Prop: Applicative,
      E,
      L: Monoid,
      S
  ]: Prop[ReaderWriterStateT[F, E, L, S, *]] =
    Prop[F].mapK(ReaderWriterStateT.liftK)

  private[std] final class SyncProp[F[_]](implicit F: Sync[F]) extends Prop[F] {

    def get(key: String): F[Option[String]] =
      F.delay(Option(System.getProperty(key))) // thread-safe

    def modify(key: String, f: String => (String, String)): F[Option[String]] = F.delay {
      Option(System.getProperty(key)).map { value =>
        val (newValue, output) = f(value)
        System.setProperty(key, newValue)
        output
      }
    }

    def set(key: String, value: String): F[Unit] =
      F.void(F.delay(System.setProperty(key, value)))

    def update(key: String, f: String => String): F[Unit] = F.void(
      F.delay(Option(System.getProperty(key)).map(value => System.setProperty(key, f(value))))
    )

    def unset(key: String): F[Unit] = F.void(F.delay(System.clearProperty(key)))
  }
}
