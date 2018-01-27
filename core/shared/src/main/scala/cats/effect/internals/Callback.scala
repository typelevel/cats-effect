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

import scala.util.Left

private[effect] object Callback {
  type Type[-A] = Either[Throwable, A] => Unit

  /**
   * Builds a callback reference that throws any received
   * error immediately.
   */
  val report = (r: Either[Throwable, _]) =>
    r match {
      case Left(e) => throw e
      case _ => ()
    }

  /** Reusable `Right(())` reference. */
  final val rightUnit = Right(())

  /** Builds a callback with async execution. */
  def async[A](cb: Type[A]): Type[A] =
    async(null, cb)

  /**
   * Builds a callback with async execution.
   *
   * Also pops the `Connection` just before triggering
   * the underlying callback.
   */
  def async[A](conn: Connection, cb: Type[A]): Type[A] =
    value => TrampolineEC.immediate.execute(
      new Runnable {
        def run(): Unit = {
          if (conn ne null) conn.pop()
          cb(value)
        }
      })
}
