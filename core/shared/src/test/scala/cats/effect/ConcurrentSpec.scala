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

package cats
package effect
package concurrent

import cats.laws.discipline._
import cats.implicits._
import cats.effect.testkit.TestContext

import org.specs2.mutable.Specification

import org.typelevel.discipline.specs2.mutable.Discipline

import scala.concurrent.duration._

class ConcurrentSpec extends Specification with Discipline with BaseSpec { outer =>

  sequential

  "concurrent" should {
    "have a parallel instance that" should {
      "run in parallel" in real {
        val x = IO.sleep(2.seconds) >> IO.pure(1)
        val y = IO.sleep(2.seconds) >> IO.pure(2)

        timeout(List(x,y).parSequence, 3.seconds).flatMap { res =>
          IO {
            res must beEqualTo(List(1,2))
          }
        }
      }
    }
  }

  {
    implicit val ticker = Ticker(TestContext())

    checkAll(
      "IO",
      ParallelTests[IO].parallel[Int, Int])
  }

  //TODO remove once we have these as derived combinators again
  private def timeoutTo[F[_], E, A](fa: F[A], duration: FiniteDuration, fallback: F[A])(
    implicit F: Temporal[F, E]
  ): F[A] =
    F.race(fa, F.sleep(duration)).flatMap {
      case Left(a)  => F.pure(a)
      case Right(_) => fallback
    }

  private def timeout[F[_], A](fa: F[A], duration: FiniteDuration)(implicit F: Temporal[F, Throwable]): F[A] = {
    val timeoutException = F.raiseError[A](new RuntimeException(duration.toString))
    timeoutTo(fa, duration, timeoutException)
  }

}
