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

package cats.effect.internals

import cats.effect.IO

import scala.concurrent.duration.Duration

private[effect] object IOPlatform {

  /**
   * Javascript specific function that should block for the result
   * of an IO task, unfortunately blocking is not possible for JS,
   * so all we can do is to throw an error.
   */
  def unsafeResync[A](ioa: IO[A], limit: Duration): Option[A] = {
    val _ = limit // is used on the JVM
    val cb = new ResyncCallback[A]
    val cancel = ioa.unsafeRunCancelable(cb)

    cb.value match {
      case Right(a) => Some(a)
      case Left(e)  => throw e
      case null     =>
        // Triggering cancellation first
        cb.isActive = false
        cancel.unsafeRunAsyncAndForget()
        throw new UnsupportedOperationException(
          "cannot synchronously await result on JavaScript; use runAsync or unsafeRunAsync"
        )
    }
  }

  /**
   * Establishes the maximum stack depth for `IO#map` operations
   * for JavaScript.
   *
   * The default for JavaScript is 32, from which we subtract 1
   * as an optimization.
   */
  final val fusionMaxStackDepth = 31

  /** Returns `true` if the underlying platform is the JVM,
   * `false` if it's JavaScript. */
  final val isJVM = false

  /**
   * Internal — used in the implementation of [[unsafeResync]].
   */
  final private class ResyncCallback[A] extends (Either[Throwable, A] => Unit) {
    var isActive = true
    var value: Either[Throwable, A] = _

    def apply(value: Either[Throwable, A]): Unit =
      if (isActive) {
        isActive = false
        this.value = value
      } else
        value match {
          case Left(e) => Logger.reportFailure(e)
          case _       => ()
        }
  }
}
