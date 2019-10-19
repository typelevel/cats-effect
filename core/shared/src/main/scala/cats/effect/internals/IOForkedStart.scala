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

package cats.effect
package internals

import scala.annotation.tailrec

/**
 * A marker for detecting asynchronous tasks that will fork execution.
 *
 * We prefer doing this because extraneous asynchronous boundaries
 * are more expensive than doing this check.
 *
 * N.B. the rule for start functions being marked via `ForkedStart`
 * is that the injected callback MUST BE called after a full
 * asynchronous boundary.
 */
abstract private[effect] class IOForkedStart[+A] extends Start[A]

private[effect] object IOForkedStart {

  /**
   * Given a task, returns one that has a guaranteed
   * logical fork on execution.
   *
   * The equivalent of `IO.shift *> task` but it tries
   * to eliminate extraneous async boundaries. In case the
   * task is known to fork already, then this introduces
   * a light async boundary instead.
   */
  def apply[A](task: IO[A], cs: ContextShift[IO]): IO[A] =
    if (detect(task)) task
    else cs.shift.flatMap(_ => task)

  /**
   * Returns `true` if the given task is known to fork execution,
   * or `false` otherwise.
   */
  @tailrec def detect(task: IO[_], limit: Int = 8): Boolean =
    if (limit > 0) {
      task match {
        case IO.Async(k, _)                => k.isInstanceOf[IOForkedStart[_]]
        case IO.Bind(other, _)             => detect(other, limit - 1)
        case IO.Map(other, _, _)           => detect(other, limit - 1)
        case IO.ContextSwitch(other, _, _) => detect(other, limit - 1)
        case _                             => false
      }
    } else {
      false
    }
}
