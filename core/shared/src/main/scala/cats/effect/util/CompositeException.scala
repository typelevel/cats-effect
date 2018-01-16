/*
 * Copyright 2017 Typelevel
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

package cats.effect.util

/** A composite exception represents a list of exceptions
  * caught from evaluating multiple independent IO actions
  * and that need to be signaled together.
  */
class CompositeException(val errors: List[Throwable])
  extends RuntimeException() with Serializable {

  def this(args: Throwable*) = this(args.toList)

  override def toString: String = {
    getClass.getName + (
      if (errors.isEmpty) "" else {
        val (first, last) = errors.splitAt(2)
        val str = first.map(e => s"${e.getClass.getName}: ${e.getMessage}").mkString(", ")
        val reasons = if (last.nonEmpty) str + "..." else str
        "(" + reasons + ")"
      })
  }
}
