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

import org.scalatest.{FunSuite, Matchers}
import cats.effect.internals.TrampolineEC.immediate

import scala.concurrent.ExecutionContext
import cats.effect.internals.IOPlatform.isJVM

import scala.collection.immutable.Queue

class TrampolineECTests extends FunSuite with Matchers {
  implicit val ec: ExecutionContext = immediate

  def executeImmediate(f: => Unit): Unit =
    ec.execute(new Runnable { def run(): Unit = f })

  test("execution should be immediate") {
    var effect = 0

    executeImmediate {
      effect += 1
      executeImmediate {
        effect += 2
        executeImmediate {
          effect += 3
        }
      }
    }

    effect shouldEqual 1 + 2 + 3
  }

  test("concurrent execution") {
    var effect = List.empty[Int]

    executeImmediate {
      executeImmediate { effect = 1 :: effect }
      executeImmediate { effect = 2 :: effect }
      executeImmediate { effect = 3 :: effect }
    }

    effect shouldEqual List(1, 2, 3)
  }

  test("stack safety") {
    var effect = 0
    def loop(n: Int, acc: Int): Unit =
      executeImmediate {
        if (n > 0) loop(n - 1, acc + 1)
        else effect = acc
      }

    val n = if (isJVM) 100000 else 5000
    loop(n, 0)

    effect shouldEqual n
  }

  test("on blocking it should fork") {
    assume(isJVM, "test relevant only for the JVM")
    import concurrent.blocking

    var effects = Queue.empty[Int]
    executeImmediate {
      executeImmediate { effects = effects.enqueue(4) }
      executeImmediate { effects = effects.enqueue(4) }

      effects = effects.enqueue(1)
      blocking { effects = effects.enqueue(2) }
      effects = effects.enqueue(3)
    }

    effects shouldBe Queue(1, 4, 4, 2, 3)
  }

  test("thrown exceptions should trigger scheduled execution") {
    val dummy1 = new RuntimeException("dummy1")
    val dummy2 = new RuntimeException("dummy1")
    var effects = 0

    try {
      executeImmediate {
        executeImmediate { effects += 1 }
        executeImmediate { effects += 1 }
        executeImmediate {
          executeImmediate { effects += 1 }
          executeImmediate { effects += 1 }
          throw dummy2
        }
        throw dummy1
      }
      fail("should have thrown exception")
    } catch {
      case `dummy2` =>
        effects shouldBe 4
    }
  }
}
