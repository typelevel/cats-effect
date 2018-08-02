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
    * Returns a [[ContextShift]] instance for [[IO]].
    *
    * Note that even when JS does not require ExecutionContext,
    * it is is here required to provide default instance of test EC for testing purposes.
    *
    */
  implicit def contextShift(implicit ec: ExecutionContext = ExecutionContext.Implicits.global ): ContextShift[IO] =  {
    ec match {
      case ExecutionContext.Implicits.global =>
        IOContextShift.global
      case _ =>
        IOContextShift.deferred(ec)
    }
  }


}
