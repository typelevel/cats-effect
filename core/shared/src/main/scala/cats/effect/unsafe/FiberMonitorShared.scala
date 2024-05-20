/*
 * Copyright 2020-2024 Typelevel
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

package cats.effect.unsafe

import cats.effect.{IOFiber, Trace}
import cats.effect.tracing.Tracing

private[unsafe] abstract class FiberMonitorShared {

  protected val newline = System.lineSeparator()
  protected val doubleNewline = s"$newline $newline"

  protected def fiberString(fiber: IOFiber[_], trace: Trace, status: String): String = {
    val id = System.identityHashCode(fiber).toHexString
    val prefixedTrace = if (trace.toList.isEmpty) "" else newline + Tracing.prettyPrint(trace)
    s"cats.effect.IOFiber@$id $status$prefixedTrace"
  }

  protected def printFibers(fibers: Map[IOFiber[_], Trace], status: String)(
      print: String => Unit): Unit =
    fibers foreach {
      case (fiber, trace) =>
        print(doubleNewline)
        print(fiberString(fiber, trace, status))
    }

}
