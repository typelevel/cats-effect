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

/** A mapping function type that is also able to handle errors,
  * being the equivalent of:
  *
  * ```
  * Either[Throwable, A] => R
  * ```
  *
  * Internal to `IO`'s implementations, used to specify
  * error handlers in their respective `Bind` internal states.
  */
private[effect] abstract class Mapping[-A, +R]
  extends (A => R) { self =>

  def apply(a: A): R
  def error(e: Throwable): R

  final def choose(value: Either[Throwable, A]): R =
    value match {
      case Right(a) => apply(a)
      case Left(e) => error(e)
    }
}

private[effect] object Mapping {
  /** Builds a [[Mapping]] instance. */
  def apply[A, R](fa: A => R, fe: Throwable => R): Mapping[A, R] =
    new Fold(fa, fe)

  /** Builds a [[Mapping]] instance that maps errors,
    * otherwise mirroring successful values (identity).
    */
  def onError[R](fe: Throwable => R): Mapping[Any, R] =
    new OnError(fe)

  /** [[Mapping]] reference that only handles errors,
    * useful for quick filtering of `onErrorHandleWith` frames.
    */
  final class OnError[+R](fe: Throwable => R)
    extends Mapping[Any, R] {

    def error(e: Throwable): R = fe(e)
    def apply(a: Any): R =
      throw new NotImplementedError("Transformation.OnError.success")
  }

  private final class Fold[-A, +R](fa: A => R, fe: Throwable => R)
    extends Mapping[A, R] {

    def apply(a: A): R = fa(a)
    def error(e: Throwable): R = fe(e)
  }
}