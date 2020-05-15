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

package ce3

import cats.{Defer, MonadError}

trait Sync[F[_]] extends MonadError[F, Throwable] with Clock[F] with Defer[F] {
  def delay[A](thunk: => A): F[A]

  def defer[A](thunk: => F[A]): F[A] =
    flatMap(delay(thunk))(x => x)
}

object Sync {
  def apply[F[_]](implicit F: Sync[F]): Sync[F] = F
}
