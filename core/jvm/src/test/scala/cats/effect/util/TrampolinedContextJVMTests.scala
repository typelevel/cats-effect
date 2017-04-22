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

import java.util.concurrent.{CountDownLatch, TimeUnit}
import org.scalatest.FunSuite
import scala.concurrent.ExecutionContext

class TrampolinedContextJVMTests extends FunSuite {
  test("on blocking it should fork") {
    import concurrent.blocking
    val ec = TrampolinedContext(ExecutionContext.global)

    var effect = 0
    val start = new CountDownLatch(1)
    val finish = new CountDownLatch(2)

    ec.execute(new Runnable {
      def run(): Unit = {
        ec.execute(new Runnable {
          def run(): Unit = ec.synchronized {
            start.await()
            effect += 20
            finish.countDown()
          }
        })

        ec.execute(new Runnable {
          def run(): Unit = ec.synchronized {
            start.await()
            effect += 20
            finish.countDown()
          }
        })

        effect += 3
        blocking { effect += 10 }
        effect += 3
      }
    })

    assert(effect === 16)

    start.countDown()
    finish.await(30, TimeUnit.SECONDS)
    assert(effect === 56)
  }
}
