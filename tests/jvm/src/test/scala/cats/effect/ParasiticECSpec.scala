/*
 * Copyright 2020-2024 Typelevel
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

import cats.effect.testkit.TestInstances
import cats.syntax.all._

import org.scalacheck.Arbitrary

import scala.concurrent.duration._

class ParasiticECSpec extends BaseSpec with TestInstances {

  override def executionTimeout: FiniteDuration = 60.seconds

  "IO monad" should {
    "evaluate fibers correctly in presence of a parasitic execution context" in real {
      val test = {
        implicit val ticker = Ticker()

        IO(implicitly[Arbitrary[IO[Int]]].arbitrary.sample.get).flatMap { io =>
          IO.delay(io.eqv(io))
        }
      }

      val iterations = 15000

      List.fill(iterations)(test).sequence.map(_.count(identity)).flatMap { c =>
        IO {
          c mustEqual iterations
        }
      }
    }
  }
}
