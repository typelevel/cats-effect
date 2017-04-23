/*
 * Copyright 2017 Typelevel
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

import simulacrum._

/**
 * A monad that can suspend the execution of side-effects
 * in the `F[_]` context.
 */
@typeclass
trait Sync[F[_]] extends MonadError[F, Throwable] {
  /**
   * Suspends the evaluation of an `F` reference.
   *
   * Equivalent with `FlatMap.flatten` for pure expressions,
   * the purpose of this function is to suspend side-effects
   * in `F`.
   */
  def suspend[A](thunk: => F[A]): F[A]

  /**
   * Lifts any by-name parameter in the `F` context.
   *
   * Equivalent with `Applicative.pure` for pure expressions,
   * the purpose of this function is to suspend side-effects
   * in `F`.
   */
  def delay[A](thunk: => A): F[A] = suspend(pure(thunk))
}
