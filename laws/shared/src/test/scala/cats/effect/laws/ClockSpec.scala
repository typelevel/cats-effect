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
package laws

import cats.data.ContT
import cats.effect.kernel.testkit.freeEval._

import org.specs2.mutable.Specification
import org.typelevel.discipline.specs2.mutable.Discipline

class ClockSpec extends Specification with Discipline with BaseSpec {

  // we only need to test the ones that *aren't* also Sync
  checkAll("ContT[FreeEitherSync, Int, *]", ClockTests[ContT[FreeEitherSync, Int, *]].clock)
}
