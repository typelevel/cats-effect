/*
 * Copyright 2020-2022 Typelevel
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

import java.time.Instant

private[effect] abstract class IOCompanionPlatform { this: IO.type =>

  def blocking[A](thunk: => A): IO[A] =
    // do our best to mitigate blocking
    IO.cede *> apply(thunk).guarantee(IO.cede)

  private[effect] def interruptible[A](many: Boolean, thunk: => A): IO[A] = {
    val _ = many
    blocking(thunk)
  }

  def interruptible[A](thunk: => A): IO[A] = interruptible(false, thunk)

  def interruptibleMany[A](thunk: => A): IO[A] = interruptible(true, thunk)

  def suspend[A](hint: Sync.Type)(thunk: => A): IO[A] = {
    val _ = hint
    apply(thunk)
  }

  def realTimeInstant: IO[Instant] = asyncForIO.realTimeInstant

}
