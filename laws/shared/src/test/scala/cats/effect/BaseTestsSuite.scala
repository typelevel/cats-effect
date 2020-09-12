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

package cats.effect

import cats.effect.internals.TestUtils
import cats.effect.laws.util.{TestContext, TestInstances}
import munit.{DisciplineSuite, Location}

import org.typelevel.discipline.Laws

class BaseTestsSuite extends DisciplineSuite with TestInstances with TestUtils {

  /** For tests that need a usable [[TestContext]] reference. */
  def testAsync[A](name: String)(f: TestContext => Unit)(implicit loc: Location): Unit =
    // Overriding System.err
    test(name)(silenceSystemErr(f(TestContext())))(loc)

  def checkAllAsync(name: String, f: TestContext => Laws#RuleSet): Unit = {
    val context = TestContext()
    val ruleSet = f(context)

    for ((id, prop) <- ruleSet.all.properties)
      property(name + "." + id) {
        silenceSystemErr(prop)
      }
  }
}
