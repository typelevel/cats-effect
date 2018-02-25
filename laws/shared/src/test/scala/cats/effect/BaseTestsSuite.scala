/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

import cats.effect.laws.util.{TestContext, TestInstances}
import cats.kernel.Eq
import org.scalactic.source
import org.scalatest.prop.Checkers
import org.scalatest.{FunSuite, Matchers, Tag}
import org.typelevel.discipline.Laws
import org.typelevel.discipline.scalatest.Discipline

class BaseTestsSuite extends FunSuite with Matchers with Checkers with Discipline with TestInstances {
  /** For tests that need a usable [[TestContext]] reference. */
  def testAsync[A](name: String, tags: Tag*)(f: TestContext => Unit)
    (implicit pos: source.Position): Unit = {

    test(name, tags:_*)(f(TestContext()))(pos)
  }

  /** For discipline tests. */
  def checkAllAsync(name: String, f: TestContext => Laws#RuleSet) {
    val context = TestContext()
    val ruleSet = f(context)

    for ((id, prop) ← ruleSet.all.properties)
      test(name + "." + id) {
        check(prop)
      }
  }

  implicit def eqThrowable: Eq[Throwable] =
    Eq.fromUniversalEquals[Throwable]
}
