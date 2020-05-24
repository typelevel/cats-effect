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

// TODO: Track information about what combinator was used etc.
final case class StackTraceLine(className: String, methodName: String, fileName: String, lineNumber: Int) {
  import StackTraceLine._
  def demangled: StackTraceLine = {
    val newClassName = className.replaceAll("\\$", "")
    val newMethodName = anonfunRegex.findFirstMatchIn(methodName) match {
      case Some(mat) => mat.group(1)
      case None => methodName
    }
    StackTraceLine(newClassName, newMethodName, fileName, lineNumber)
  }
}

object StackTraceLine {
  val anonfunRegex = "^\\$+anonfun\\$+(.+)\\$+\\d+$".r

  def fromStackTraceElement(ste: StackTraceElement): StackTraceLine =
    StackTraceLine(ste.getClassName, ste.getMethodName, ste.getFileName, ste.getLineNumber)
}
