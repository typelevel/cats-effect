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

package cats.effect

import cats.effect.laws.discipline.{UConcurrentTests}
import cats.kernel.laws.discipline.MonoidTests
import cats.implicits._
import cats.effect.laws.discipline.arbitrary._

class UIOTests extends BaseTestsSuite {

  checkAllAsync("UIO", implicit ec => MonoidTests[UIO[Int]].monoid)
  checkAllAsync("UIO", implicit ec => UConcurrentTests[UIO].uconcurrent[Int, Int, Int])
}
