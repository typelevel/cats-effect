/*
 * Copyright 2020-2022 Typelevel
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
 * A purely functional mutex.
 *
 * A mutex is a concurrency primitive that can be used to give access to a resource to only one
 * fiber at a time; e.g. a [[cats.effect.kernel.Ref]]`
 *
 * {{{
 * // Assuming some resource r that should not be used concurrently.
 *
 * Mutex[IO].flatMap { mutex =>
 *   mutex.lock.surround {
 *     // Here you can use r safely.
 *     IO(r.mutate(...))
 *   }
 * }
 * }}}
 *
 * @see
 *   [[cats.effect.std.AtomicCell]]
 */
abstract class Mutex[F[_]] {

  /**
   * Returns a [[cats.effect.kernel.Resource]] that acquires the lock, holds it for the lifetime
   * of the resource, then releases it.
   */
  def lock: Resource[F, Unit]

  /**
   * Modify the context `F` using natural transformation `f`.
   */
  def mapK[G[_]](f: F ~> G)(implicit G: MonadCancel[G, _]): Mutex[G]
}

object Mutex {

  /**
   * Creates a new `Mutex`.
   */
  def apply[F[_]](implicit F: GenConcurrent[F, _]): F[Mutex[F]] =
    Semaphore[F](n = 1).map(sem => new Impl(sem))

  /**
   * Creates a new `Mutex`. Like `apply` but initializes state using another effect constructor.
   */
  def in[F[_], G[_]](implicit F: Sync[F], G: Async[G]): F[Mutex[G]] =
    Semaphore.in[F, G](n = 1).map(sem => new Impl(sem))

  private final class Impl[F[_]](sem: Semaphore[F]) extends Mutex[F] {
    override final val lock: Resource[F, Unit] =
      sem.permit

    override def mapK[G[_]](f: F ~> G)(implicit G: MonadCancel[G, _]): Mutex[G] =
      new Impl(sem.mapK(f))
  }
}
