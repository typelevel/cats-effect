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

package cats.effect.internals

import org.scalacheck._
import org.scalatest._
import org.scalatest.prop.Checkers

class AndThenTests extends FunSuite with Matchers with Checkers {
  import Prop._

  test("compose a chain of functions with andThen") {
    check { (i: Int, fs: List[Int => Int]) =>
      val result = fs.map(AndThen.of(_)).reduceOption(_.andThen(_)).map(_(i))
      val expect = fs.reduceOption(_.andThen(_)).map(_(i))

      result == expect
    }
  }

  test("compose a chain of functions with compose") {
    check { (i: Int, fs: List[Int => Int]) =>
      val result = fs.map(AndThen.of(_)).reduceOption(_.compose(_)).map(_(i))
      val expect = fs.reduceOption(_.compose(_)).map(_(i))

      result == expect
    }
  }

  test("andThen is stack safe") {
    val count = if (IOPlatform.isJVM) 500000 else 1000
    val fs = (0 until count).map(_ => { i: Int => i + 1 })
    val result = fs.foldLeft(AndThen.of((x: Int) => x))(_.andThen(_))(42)

    result shouldEqual (count + 42)
  }

  test("compose is stack safe") {
    val count = if (IOPlatform.isJVM) 500000 else 1000
    val fs = (0 until count).map(_ => { i: Int => i + 1 })
    val result = fs.foldLeft(AndThen.of((x: Int) => x))(_.compose(_))(42)

    result shouldEqual (count + 42)
  }

  test("Function1 andThen is stack safe") {
    val count = if (IOPlatform.isJVM) 50000 else 1000
    val start: (Int => Int) = AndThen((x: Int) => x)
    val fs = (0 until count).foldLeft(start) { (acc, _) =>
      acc.andThen(_ + 1)
    }
    fs(0) shouldEqual count
  }

  test("toString") {
    AndThen((x: Int) => x).toString should startWith("AndThen$")
  }

  // Utils
  val id = AndThen((x: Int) => x)
}