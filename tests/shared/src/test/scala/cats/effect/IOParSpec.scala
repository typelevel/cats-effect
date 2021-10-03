/*
 * Copyright 2020-2021 Typelevel
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

import cats.laws.discipline.ApplicativeTests
import cats.effect.implicits._
import org.typelevel.discipline.specs2.mutable.Discipline

class IOParSpec extends BaseSpec with Discipline {

  sequential

  {
    implicit val ticker = Ticker()

    checkAll("Applicative[IO.Par]", ApplicativeTests[IO.Par].applicative[Int, Int, Int])
  }
}
