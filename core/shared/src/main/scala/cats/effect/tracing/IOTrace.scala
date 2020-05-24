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

package cats.effect.tracing

final case class IOTrace(frames: Vector[TraceFrame], omitted: Int) {

  def prettyPrint(): Unit = {
    System.err.println(s"IOTrace: $omitted omitted frames")
    val render = loop("", 0, true, frames.toList)
    System.err.println(render)
  }

  private def loop(acc: String, indent: Int, init: Boolean, rest: List[TraceFrame]): String = {
    val TurnRight = "╰"
    val InverseTurnRight = "╭"
    val TurnDown = "╮"
    val Junction = "├"
    val Line = "│"

    rest match {
      case k :: ks => {
        val acc2 = if (init) {
          InverseTurnRight + s" ${k.op}\n"
        } else {
          Junction + s" ${k.op}\n"
        }

        val inner = Line + " " + TurnRight + TurnDown + "\n"
        val demangled = k.line.map(_.demangled)
        val traceLine = Line + "  " + TurnRight + " " + demangled.map(l => s"${l.className}.${l.methodName} (${l.fileName}:${l.lineNumber})").mkString + "\n"

        loop(acc + acc2 + inner + traceLine + Line + "\n", indent, false, ks)
      }
      case Nil => {
        acc + TurnRight + " Done"
      }
    }
  }

}
