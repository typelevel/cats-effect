/*
 * Copyright 2020-2021 Typelevel
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
package testkit

import org.specs2.mutable.Specification

class SyntaxSpec extends Specification {

  "testkit syntax" >> ok

  def testControlSyntax = {
    import syntax.testControl._

    val program: IO[Int] = IO.pure(42)

    program.execute() { (control, results) =>
      val c: TestControl = control
      val r: () => Option[Either[Throwable, Int]] = results

      val _ = {
        val _ = c
        r
      }

      ()
    }

    program.executeFully(): Option[Either[Throwable, Int]]
  }
}
