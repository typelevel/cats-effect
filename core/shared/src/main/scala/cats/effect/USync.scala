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

import cats.Monad
import simulacrum._

@typeclass
trait USync[F[_]] extends Monad[F] {
  def delayCatch[A](thunk: => A): F[Either[Throwable, A]]

  def delayUnit(thunk: => Unit): F[Unit] =
    map(delayCatch(thunk))(_.right.getOrElse(()))

  def delayDefault[A](thunk: => A)(a: A): F[A] =
    map(delayCatch(thunk))(_.right.getOrElse(a))

  def bewareDelayNoCatch[A](thunk: => A): F[A] =
    map(delayCatch[A](thunk))(_.right.get)
}
