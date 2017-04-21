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

import org.scalatest._
import org.scalacheck._

class AndThenTests extends FunSuite with Matchers {
  import Prop._

  test("compose a chain of functions") {
    forAll { (i: Int, fs: List[Int => Int]) =>
      val result = fs.map(AndThen(_)).reduceOption(_.andThen(_)).map(_(i))
      val expect = fs.reduceOption(_.andThen(_)).map(_(i))

      result == expect
    }
  }

  test("be stack safe") {
    val fs = (0 until 50000).map(_ => { i: Int => i + 1 })
    val result = fs.map(AndThen(_)).reduceLeft(_.andThen(_))(42)

    result shouldEqual 50042
  }
}
