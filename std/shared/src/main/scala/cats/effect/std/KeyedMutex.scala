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

package cats
package effect
package std

import cats.effect.kernel._
import cats.syntax.all._

/**
 * A purely functional keyed mutex.
 *
 * A mutex is a concurrency primitive that can be used to give access to a resource to only one
 * fiber at a time; e.g. a [[cats.effect.kernel.Ref]].
 *
 * '''Note''': This lock is not reentrant, thus this
 * `mutex.lock(key).surround(mutex.lock(key).use_)` will deadlock.
 *
 * @see
 *   [[cats.effect.std.Mutex]]
 */
abstract class KeyedMutex[F[_], K] {

  /**
   * Returns a [[cats.effect.kernel.Resource]] that acquires the lock for the given `key`, holds
   * it for the lifetime of the resource, then releases it.
   */
  def lock(key: K): Resource[F, Unit]

  /**
   * Modify the context `F` using natural transformation `f`.
   */
  def mapK[G[_]](f: F ~> G)(implicit G: MonadCancel[G, _]): KeyedMutex[G, K]
}

object KeyedMutex {

  /**
   * Creates a new `KeyedMutex`.
   */
  def apply[F[_], K](implicit F: Concurrent[F]): F[KeyedMutex[F, K]] =
    ???

  /**
   * Creates a new `KeyedMutex`. Like `apply` but initializes state using another effect
   * constructor.
   */
  def in[F[_], G[_], K](implicit F: Sync[F], G: Async[G]): F[KeyedMutex[G, K]] =
    ???
}
