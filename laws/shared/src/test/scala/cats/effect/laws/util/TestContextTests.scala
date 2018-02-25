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

package cats.effect.laws.util

import cats.effect.BaseTestsSuite
import scala.concurrent.Future
import scala.util.Success

class TestContextTests extends BaseTestsSuite {
  testAsync("recursive loop") { implicit ec =>
    def loop(n: Int, sum: Long): Future[Long] = {
      if (n <= 0) Future(sum) else {
        val f1 = Future(n - 1)
        val f2 = Future(sum + n)

        f1.flatMap { newN =>
          f2.flatMap { newSum => loop(newN, newSum) }
        }
      }
    }

    val n = 10000
    val f = loop(n, 0)
    assert(f.value === None)

    ec.tick()
    assert(f.value === Some(Success(n * (n + 1) / 2)))
  }

  testAsync("reportFailure") { ec =>
    val dummy = new RuntimeException("dummy")
    var effect = false

    ec.execute(new Runnable {
      def run(): Unit = {
        ec.execute(new Runnable {
          def run(): Unit =
            effect = true
        })

        throw dummy
      }
    })

    assert(effect === false)
    assert(ec.state.lastReportedFailure === None)

    ec.tick()

    assert(effect === true)
    assert(ec.state.lastReportedFailure === Some(dummy))
  }
}
