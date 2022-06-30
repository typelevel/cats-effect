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

import cats.effect.tracing.Tracing

final case class FiberInfo(fiber: Fiber[IO, _, _], state: FiberState, trace: FiberTrace) {
  def pretty = {
    val id = System.identityHashCode(fiber).toHexString
    val trace = fiber match {
      case ioFiber: IOFiber[_] => ioFiber.prettyPrintTrace()
      case _ => ""
    }
    val prefixedTrace = if (trace.isEmpty) "" else "\n" + trace
    s"cats.effect.IOFiber@$id $state$prefixedTrace"
  }
}

sealed abstract class FiberState(override val toString: String)
    extends Product
    with Serializable

object FiberState {
  case object Running extends FiberState("RUNNING")
  case object Blocked extends FiberState("BLOCKED")
  case object Yielding extends FiberState("YIELDING")
  case object Waiting extends FiberState("WAITING")
  case object Active extends FiberState("ACTIVE")
}

final case class FiberTrace(frames: List[StackTraceElement]) {
  def pretty: String = Tracing.prettyPrint(frames)
}
