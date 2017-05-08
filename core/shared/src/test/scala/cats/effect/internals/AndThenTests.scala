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

package cats.effect.internals

import org.scalacheck._
import org.scalatest._
import org.scalatest.prop.Checkers

class AndThenTests extends FunSuite with Matchers with Checkers {
  import Prop._

  test("compose a chain of functions with andThen") {
    check { (i: Int, fs: List[Int => Int]) =>
      val result = fs.map(AndThen(_)).reduceOption(_.andThen(_)).map(_(i))
      val expect = fs.reduceOption(_.andThen(_)).map(_(i))

      result == expect
    }
  }

  test("compose a chain of functions with compose") {
    check { (i: Int, fs: List[Int => Int]) =>
      val result = fs.map(AndThen(_)).reduceOption(_.compose(_)).map(_(i))
      val expect = fs.reduceOption(_.compose(_)).map(_(i))

      result == expect
    }
  }


  test("be stack safe") {
    val fs = (0 until 50000).map(_ => { i: Int => i + 1 })
    val result = fs.map(AndThen(_)).reduceLeft(_.andThen(_))(42)

    result shouldEqual 50042
  }

  test("handles error if handler is in chain") {
    val dummy = new RuntimeException("dummy")

    check { (i: Int, fs1: List[Int => Int], fs2: List[Int => Int]) =>
      val result = {
        val before = fs1.map(AndThen(_)).reduceOption(_.andThen(_)).getOrElse(id)
        val after = fs2.map(rethrow).reduceOption(_.andThen(_)).getOrElse(id)

        val handler = AndThen((x: Int) => x, {
          case `dummy` => i
          case e => throw e
        })

        before.andThen(handler).andThen(after)
          .error(dummy, e => throw e)
      }

      val expect = fs2
        .reduceOption(_.andThen(_))
        .getOrElse((x: Int) => x)
        .apply(i)

      result == expect
    }
  }

  test("if Simple function throws, tries to recover") {
    val dummy = new RuntimeException("dummy")

    val f = AndThen((_: Int) => throw dummy).andThen(
      AndThen((x: Int) => x, {
        case `dummy` => 100
        case e => throw e
      }))

    f(0) shouldEqual 100
  }

  test("if ErrorHandler throws, tries to recover") {
    val dummy = new RuntimeException("dummy")

    val f = AndThen((_: Int) => throw dummy, e => throw e).andThen(
      AndThen((x: Int) => x, {
        case `dummy` => 100
        case e => throw e
      }))

    f(0) shouldEqual 100
  }

  test("throws if no ErrorHandler defined in chain") {
    val dummy1 = new RuntimeException("dummy1")
    val f = AndThen((_: Int) => throw dummy1).andThen(AndThen((x: Int) => x + 1))

    try f(0) catch { case `dummy1` => () }

    val dummy2 = new RuntimeException("dummy2")
    try f.error(dummy2, e => throw e) catch { case `dummy2` => () }
  }

  test("toString") {
    AndThen((x: Int) => x).toString should startWith("AndThen$")
  }

  // Utils
  val id = AndThen((x: Int) => x)
  def rethrow(f: Int => Int) = AndThen(f, e => throw e)
}
