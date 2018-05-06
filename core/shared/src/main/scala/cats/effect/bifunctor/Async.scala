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

package cats
package effect.bifunctor

import scala.annotation.implicitNotFound
import scala.util.Either

@implicitNotFound("""Cannot find implicit value for Async[${F}, ${E}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Scheduler or some equivalent type.""")
trait Async[F[_], E] extends Sync[F, E] {
  /**
   * Creates a simple, noncancelable `F[A]` instance that
   * executes an asynchronous process on evaluation.
   *
   * The given function is being injected with a side-effectful
   * callback for signaling the final result of an asynchronous
   * process.
   *
   * @param k is a function that should be called with a
   *       callback for signaling the result once it is ready
   */
  def async[A](k: (Either[E, A] => Unit) => Unit): F[A]

  /**
    * Returns a non-terminating `F[_]`, that never completes
    * with a result, being equivalent to `async(_ => ())`
    */
  def never[A]: F[A] = async(_ => ())
}
