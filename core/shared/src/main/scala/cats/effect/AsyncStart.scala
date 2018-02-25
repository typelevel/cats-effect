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

package cats.effect

import simulacrum._

/**
 * Type-class describing concurrent execution via the `start` operation.
 *
 * The `start` operation returns a [[Fiber]] that can be attached
 * to a process that runs concurrently and that can be joined, in order
 * to wait for its result or that can be cancelled at a later time,
 * in case of a race condition.
 */
@typeclass
trait AsyncStart[F[_]] extends Async[F] {
  /**
   * Start concurrent execution of the source suspended in the `F` context.
   *
   * Returns a [[Fiber]] that can be used to either join or cancel
   * the running computation, being similar in spirit (but not
   * in implementation) to starting a thread.
   */
  def start[A](fa: F[A]): F[Fiber[F, A]]
}
