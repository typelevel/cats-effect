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

package cats.effect.tracing

import cats.effect.{Async, BaseSpec, IO}
import cats.effect.testkit.TestInstances

class TracingSpec extends BaseSpec with TestInstances {

  "IO.delay" should {
    "generate identical traces" in {
      val f = () => println("foo")
      val a = IO(f())
      val b = IO(f())
      (a, b) match {
        case (IO.Delay(_, eventA), IO.Delay(_, eventB)) => eventA eq eventB
        case _ => false
      }
    }

    "generate unique traces" in {
      val a = IO(println("foo"))
      val b = IO(println("bar"))
      (a, b) match {
        case (IO.Delay(_, eventA), IO.Delay(_, eventB)) => eventA ne eventB
        case _ => false
      }
    }
  }

  "Async.delay" should {
    "generate identical traces" in {
      val f = () => println("foo")
      val a = Async[IO].delay(f())
      val b = Async[IO].delay(f())
      (a, b) match {
        case (IO.Delay(_, eventA), IO.Delay(_, eventB)) => eventA eq eventB
        case _ => false
      }
    }

    "generate unique traces" in {
      val a = Async[IO].delay(println("foo"))
      val b = Async[IO].delay(println("bar"))
      (a, b) match {
        case (IO.Delay(_, eventA), IO.Delay(_, eventB)) => eventA ne eventB
        case _ => false
      }
    }
  }

}
