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

/** A mapping function that is also able to handle errors,
  * being the equivalent of:
  *
  * ```
  * Either[Throwable, A] => R
  * ```
  *
  * Internal to `IO`'s implementations, used to specify
  * error handlers in their respective `Bind` internal states.
  */
private[effect] abstract class IOFrame[-A, +R]
  extends (A => R) { self =>

  def apply(a: A): R
  def recover(e: Throwable): R

  final def fold(value: Either[Throwable, A]): R =
    value match {
      case Right(a) => apply(a)
      case Left(e) => recover(e)
    }
}

private[effect] object IOFrame {
  /** Builds a [[IOFrame]] instance that maps errors, but that isn't
    * defined for successful values (a partial function)
    */
  def errorHandler[A](fe: Throwable => IO[A]): IOFrame[A, IO[A]] =
    new ErrorHandler(fe)

  /** [[IOFrame]] reference that only handles errors, useful for
    * quick filtering of `onErrorHandleWith` frames.
    */
  final class ErrorHandler[A](fe: Throwable => IO[A])
    extends IOFrame[A, IO[A]] {

    def recover(e: Throwable): IO[A] = fe(e)
    def apply(a: A): IO[A] = IO.pure(a)
  }
}