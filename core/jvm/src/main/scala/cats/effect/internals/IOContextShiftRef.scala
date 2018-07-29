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

package cats.effect.internals


import cats.effect.{ContextShift, IO}

import scala.concurrent.ExecutionContext

private[effect] trait IOContextShiftRef {

  /**
    * Returns a [[ContextShift]] instance for [[IO]], built from a
    * Scala `ExecutionContext`.
    *
    * N.B. this is the JVM-specific version. On top of JavaScript
    * the implementation needs no `ExecutionContext`.
    *
    * @param ec is the execution context used for actual execution
    *        tasks (e.g. bind continuations)
    */
  implicit def contextShift(implicit ec: ExecutionContext): ContextShift[IO] =
    ec match {
      case ExecutionContext.Implicits.global =>
        IOContextShiftRef.defaultContextShift
      case _ =>
        IOContextShift(ec)
    }


}


object IOContextShiftRef {
  /** Default, reusable instance, using Scala's `global`. */
  private[internals] val defaultContextShift : ContextShift[IO] =
    IOContextShift.global

}