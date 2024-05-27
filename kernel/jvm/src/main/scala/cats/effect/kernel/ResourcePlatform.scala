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

package cats.effect.kernel

import javax.security.auth.Destroyable

/**
 * JVM-specific Resource methods
 */
private[effect] trait ResourcePlatform extends Serializable {

  /**
   * Creates a [[Resource]] by wrapping a Java
   * [[https://docs.oracle.com/javase/8/docs/api/javax/security/auth/Destroyable.html Destroyable]].
   *
   * @example
   *   {{{
   *   import java.security.KeyStore.PasswordProtection
   *   import cats.effect.{IO, Resource}
   *
   *   def passwordProtection(getPassword: IO[Array[Char]]): Resource[IO, PasswordProtection] =
   *     Resource.fromDestroyable(
   *       getPassword.map(new PasswordProtection(_))
   *     )
   *   }}}
   *
   * @example
   *   {{{
   *   import java.security.KeyStore.PasswordProtection
   *   import cats.effect.{Resource, Sync}
   *   import cats.syntax.all._
   *
   *   def passwordProtection[F[_]](getPassword: F[Array[Char]])(implicit F: Sync[F]): Resource[F, PasswordProtection] =
   *     Resource.fromDestroyable(
   *       getPassword.map(new PasswordProtection(_))
   *     )
   *   }}}
   *
   * @param acquire
   *   The effect with the resource to acquire.
   * @param F
   *   the effect type in which the resource was acquired and will be released
   * @tparam F
   *   the type of the effect
   * @tparam A
   *   the type of the destroyable resource
   * @return
   *   a Resource that will automatically destroy after use
   */
  def fromDestroyable[F[_], A <: Destroyable](acquire: F[A])(
      implicit F: Sync[F]): Resource[F, A] =
    Resource.make(acquire)(destroyable => F.delay(destroyable.destroy()))
}
