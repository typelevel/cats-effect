/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

import org.scalacheck.Prop.forAll

class ExitCodeTests extends CatsEffectSuite {
  property("fromInt(i) == fromInt(i & 0xff)") {
    forAll { (i: Int) =>
      ExitCode(i) == ExitCode(i & 0xff)
    }
  }

  property("code is in range from 0 to 255, inclusive") {
    forAll { (i: Int) =>
      val ec = ExitCode(i)
      ec.code >= 0 && ec.code < 256
    }
  }
}
