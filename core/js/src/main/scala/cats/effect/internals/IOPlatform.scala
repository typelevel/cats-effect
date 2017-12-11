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

package cats.effect.internals

import cats.effect.IO

import scala.concurrent.duration.Duration
import scala.util.Try

private[effect] object IOPlatform {
  /**
   * Javascript specific function that should block for the result
   * of an IO task, unfortunately blocking is not possible for JS,
   * so all we can do is to throw an error.
   */
  def unsafeResync[A](ioa: IO[A], limit: Duration): Option[A] = {
    throw new UnsupportedOperationException(
      "cannot synchronously await result on JavaScript; " +
      "use runAsync or unsafeRunAsync")
  }

  /**
   * Given any side-effecting function, builds a new one
   * that has the idempotency property, making sure that its
   * side effects get triggered only once
   */
  def onceOnly[A](f: A => Unit): A => Unit = {
    var wasCalled = false

    a => if (wasCalled) () else {
      wasCalled = true
      f(a)
    }
  }

  /**
   * Establishes the maximum stack depth for `IO#map` operations
   * for JavaScript. 
   *
   * The default for JavaScript is 32, from which we substract 1
   * as an optimization.
   */
  private[effect] final val fusionMaxStackDepth = 31
}
