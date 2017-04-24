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
import scala.annotation.implicitNotFound
import scala.util.Either

/**
 * A monad that can describe asynchronous computations that
 * produce exactly one result.
 */
@typeclass
@implicitNotFound("""Cannot find implicit value for Async[${F}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Strategy or some equivalent type.""")
trait Async[F[_]] extends MonadError[F, Throwable] {
  /**
   * Creates an `F[A]` instance from a provided function
   * that will have a callback injected for signaling the
   * final result of an asynchronous process.
   *
   * @param k is a function that should be called with a
   *       callback for signaling the result once it is ready
   */
  def async[A](k: (Either[Throwable, A] => Unit) => Unit): F[A]
}
