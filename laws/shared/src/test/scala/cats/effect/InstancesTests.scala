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

package cats
package effect

import cats.effect.laws.discipline.SyncTests
import cats.implicits._
import cats.laws.discipline.arbitrary._

import scala.util.Try

class InstancesTests extends BaseTestsSuite {

  checkAll("Eval", SyncTests[Eval].sync[Int, Int, Int])

  // assume exceptions are equivalent if the exception name + message
  // match as strings.
  implicit def throwableEq: Eq[Throwable] =
    Eq[String].contramap((t: Throwable) => t.toString)

  // we want exceptions which occur during .value calls to be equal to
  // each other, assuming the exceptions seem equivalent.
  implicit def eqWithTry[A: Eq]: Eq[Eval[A]] =
    Eq[Try[A]].on((e: Eval[A]) => Try(e.value))
}
