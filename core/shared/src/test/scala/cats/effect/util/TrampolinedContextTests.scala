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

import org.scalatest.FunSuite
import scala.concurrent.{ExecutionContext, Promise}

class TrampolinedContextTests extends FunSuite {
  test("execute async should execute immediately") {
    val ec = TrampolinedContext.immediate
    var effect = 0
    val p = Promise[Int]()

    ec.execute(new Runnable {
      def run(): Unit = {
        effect += 1

        ec.execute(new Runnable {
          def run(): Unit = {
            effect += 2

            ec.execute(new Runnable {
              def run(): Unit = {
                effect += 3
                p.success(effect)
              }
            })
          }
        })
      }
    })

    // Should already be executed
    assert(effect === 1 + 2 + 3)
  }

  test("report failure should work") {
    var lastError: Throwable = null

    val ec = TrampolinedContext(new ExecutionContext {
      def execute(r: Runnable): Unit = r.run()
      def reportFailure(cause: Throwable): Unit =
        lastError = cause
    })

    val ex = new RuntimeException("dummy")
    ec.execute(new Runnable { def run(): Unit = throw ex })
    assert(lastError === ex)
  }
}
