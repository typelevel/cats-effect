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

private[internals] final class IOResyncCallback[A]
  extends (Either[Throwable, A] => Unit) {

  var isActive = true
  var value: Either[Throwable, A] = _

  def apply(value: Either[Throwable, A]): Unit = {
    if (isActive) {
      isActive = false
      this.value = value
    } else value match {
      case Left(e) => Logger.reportFailure(e)
      case _ => ()
    }
  }
}
