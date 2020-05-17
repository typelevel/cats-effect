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

  def printTrace(): Unit = {
    System.err.println(s"IOTrace: $omitted omitted frames")
    frames.foreach { f =>
      f.line.foreach { l =>
        System.err.println(s"\t${f.op} at ${l.className}.${l.methodName} (${l.fileName}:${l.lineNumber})")
      }
    }
  }

}
