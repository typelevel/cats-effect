/*
 * Copyright 2020 Typelevel
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

package cats.effect

import scalajs.js

import scala.scalajs.js.Promise

private[effect] abstract class IOCompanionPlatform { this: IO.type =>

  def blocking[A](thunk: => A): IO[A] =
    apply(thunk)

  def interruptible[A](many: Boolean)(thunk: => A): IO[A] = {
    val _ = many
    apply(thunk)
  }

  def suspend[A](hint: Sync.Type)(thunk: => A): IO[A] = {
    val _ = hint
    apply(thunk)
  }

  def fromPromise[A](iop: IO[Promise[A]]): IO[A] =
    asyncForIO.fromPromise(iop)

  def realTimeDate: IO[js.Date] = asyncForIO.realTimeDate
}
