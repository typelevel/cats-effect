/*
 * Copyright 2020-2025 Typelevel
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

import cats.effect.tracing.Tracing

/**
 * Snapshot of a running [[cats.effect.Fiber]].
 *
 * @note
 *   instances of this class hold hard references to the underlying fibers. Retaining them may
 *   prevent garbage collection and cause memory leaks.
 */
sealed trait FiberInfo {

  /**
   * The underlying fiber.
   */
  def fiber: Fiber[IO, ?, ?]

  /**
   * The current state of the fiber (e.g., Running, Blocked).
   */
  def state: FiberInfo.State

  /**
   * The captured execution trace at the time of snapshot (if available).
   */
  def trace: Trace

  /**
   * Human-readable representation of the fiber.
   */
  def pretty: String
}

object FiberInfo {

  def apply(fiber: Fiber[IO, ?, ?], state: State, trace: Trace): FiberInfo =
    Impl(fiber, state, trace)

  sealed abstract class State(override val toString: String) extends Product with Serializable

  object State {
    case object Running extends State("RUNNING")
    case object Blocked extends State("BLOCKED")
    case object Yielding extends State("YIELDING")
    case object Waiting extends State("WAITING")
    case object Active extends State("ACTIVE")
  }

  private final case class Impl(
      fiber: Fiber[IO, ?, ?],
      state: State,
      trace: Trace
  ) extends FiberInfo {

    def pretty: String = {
      val id = System.identityHashCode(fiber).toHexString
      val prefixedTrace = if (trace.toList.isEmpty) "" else "\n" + Tracing.prettyPrint(trace)
      s"cats.effect.IOFiber@$id $state$prefixedTrace"
    }

  }

}
