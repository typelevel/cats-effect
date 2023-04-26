/*
 * Copyright 2020-2022 Typelevel
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

import cats.mtl.Local
import cats.mtl.laws.discipline._
import org.typelevel.discipline.specs2.mutable.Discipline

import java.util.concurrent.CancellationException

class IOMtlLocalSpec extends BaseSpec with Discipline {
  sequential

  implicit val ticker: Ticker = Ticker()

  implicit val local: Local[IO, Int] =
    // Don't try this at home
    unsafeRun(IO.local(0)).fold(
      throw new CancellationException("canceled"),
      throw _,
      _.get
    )

  checkAll("Local[IO, Int]", LocalTests[IO, Int].local[Int, Int])
}
