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

package cats.effect
package syntax

import cats.{Applicative}

trait ResourceSyntax {
  implicit def toResourceOps[F[_], A](fa: F[A]) = new ResourceOps(fa)
  implicit def toResourceFOps[F[_], A](fra: F[Resource[F, A]]) =
    new ResourceFOps(fra)

}

final class ResourceOps[F[_], A](private val fa: F[A]) extends AnyVal {
  def liftToResource(implicit F: Applicative[F]): Resource[F, A] =
    Resource.liftF(fa)
}

final class ResourceFOps[F[_], A](private val fra: F[Resource[F, A]])
    extends AnyVal {
  def flattenToResource(implicit F: Applicative[F]): Resource[F, A] =
    Resource.liftF(fra).flatMap(identity)
}
