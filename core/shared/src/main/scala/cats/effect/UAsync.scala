/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

package cats.effect

import simulacrum._

import scala.util.Either

@typeclass
trait UAsync[F[_]] extends USync[F] {
  def asyncCatch[A](k: (Either[Throwable, A] => Unit) => Unit): F[Either[Throwable, A]]

  def asyncUnit(k: (Either[Throwable, Unit] => Unit) => Unit): F[Unit] =
    map(asyncCatch(k))(_.right.getOrElse(()))

  def asyncDefault[A](k: (Either[Throwable, A] => Unit) => Unit)(a: A): F[A] =
    map(asyncCatch(k))(_.right.getOrElse(a))

  def never[A]: F[A] = map(asyncCatch[A](_ => ()))(_.right.get)

  def bewareAsyncNoCatch[A](k: (Either[Throwable, A] => Unit) => Unit): F[A] =
    map(asyncCatch[A](k))(_.right.get)
}
