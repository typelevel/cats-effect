/*
 * Copyright 2020 Typelevel
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
package internals

import cats.effect.internals.TrampolineEC.immediate
import scala.concurrent.ExecutionContext
import cats.effect.internals.Platform.isJvm
import scala.collection.immutable.Queue

import org.specs2.mutable.Specification

class TrampolineECTests extends Specification with TestUtils {
  implicit val ec: ExecutionContext = immediate

  def executeImmediate(f: => Unit): Unit =
    ec.execute(new Runnable { def run(): Unit = f })

  "trampoline EC" should {
    "execution should be immediate" in {
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
      effect must beEqualTo(1 + 2 + 3)
    }

    "concurrent execution" in {
      var effect = List.empty[Int]

      executeImmediate {
        executeImmediate { effect = 1 :: effect }
        executeImmediate { effect = 2 :: effect }
        executeImmediate { effect = 3 :: effect }
      }

      effect must beEqualTo(List(1, 2, 3))
    }

    "stack safety" in {
      var effect = 0
      def loop(n: Int, acc: Int): Unit =
        executeImmediate {
          if (n > 0) loop(n - 1, acc + 1)
          else effect = acc
        }

      val n = if (isJvm) 100000 else 5000
      loop(n, 0)

      effect must beEqualTo(n)
    }

    if (isJvm) "on blocking it should fork" in {
      import scala.concurrent.blocking

      var effects = Queue.empty[Int]
      executeImmediate {
        executeImmediate { effects = effects.enqueue(4) }
        executeImmediate { effects = effects.enqueue(4) }

        effects = effects.enqueue(1)
        blocking { effects = effects.enqueue(2) }
        effects = effects.enqueue(3)
      }

      effects must beEqualTo(Queue(1, 4, 4, 2, 3))
    }
    else "" in skipped("Only relevant on JVM")

    "thrown exceptions should get logged to System.err (immediate)" in {
      val dummy1 = new RuntimeException("dummy1")
      val dummy2 = new RuntimeException("dummy2")
      var effects = 0

      val output = catchSystemErr {
        executeImmediate {
          executeImmediate(effects += 1)
          executeImmediate(effects += 1)
          executeImmediate {
            executeImmediate(effects += 1)
            executeImmediate(effects += 1)
            throw dummy2
          }
          throw dummy1
        }
      }

      output must contain("dummy1")
      output must contain("dummy2")
      effects must beEqualTo(4)
    }

  }
}
