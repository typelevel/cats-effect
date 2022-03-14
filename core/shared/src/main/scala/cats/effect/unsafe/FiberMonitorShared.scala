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

package cats.effect.unsafe

import cats.effect.IOFiber

private[unsafe] abstract class FiberMonitorShared {

  protected val newline = System.lineSeparator()
  protected val doubleNewline = s"$newline $newline"

  protected def fiberString(fiber: IOFiber[_], status: String): String = {
    val id = System.identityHashCode(fiber).toHexString
    val trace = fiber.prettyPrintTrace()
    val prefixedTrace = if (trace.isEmpty) "" else newline + trace
    s"cats.effect.IOFiber@$id $status$prefixedTrace"
  }

  protected def printFibers(fibers: Set[IOFiber[_]], status: String)(
      print: String => Unit): Unit =
    fibers foreach { fiber =>
      print(doubleNewline)
      print(fiberString(fiber, status))
    }

}
