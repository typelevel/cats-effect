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

package cats
package effect

import scala.concurrent.ExecutionContext

import cats.effect.internals.BlockerPlatform

/**
 * An execution context that is safe to use for blocking operations.
 *
 * Used in conjunction with [[ContextShift]], this type allows us to write functions
 * that require a special `ExecutionContext` for evaluation, while discouraging the
 * use of a shared, general purpose pool (e.g. the global context).
 *
 * Instances of this class should *not* be passed implicitly.
 */
final class Blocker private (val blockingContext: ExecutionContext) extends AnyVal {

  /**
   * Like `Sync#delay` but the supplied thunk is evaluated on the blocking
   * execution context.
   */
  def delay[F[_], A](thunk: => A)(implicit F: Sync[F], cs: ContextShift[F]): F[A] =
    blockOn(F.delay(thunk))

  /**
   * Evaluates the supplied task on the blocking execution context via `blockOn`.
   */
  def blockOn[F[_], A](fa: F[A])(implicit cs: ContextShift[F]): F[A] =
    cs.blockOn(this)(fa)

  /**
   * `blockOn` as a natural transformation.
   */
  def blockOnK[F[_]](implicit cs: ContextShift[F]): F ~> F =
    λ[F ~> F](blockOn(_))
}

object Blocker extends BlockerPlatform {

  /**
   * Creates a blocker that delegates to the supplied execution context.
   *
   * This must not be used with general purpose contexts like
   * `scala.concurrent.ExecutionContext.Implicits.global`.
   */
  def liftExecutionContext(ec: ExecutionContext): Blocker = new Blocker(ec)
}
