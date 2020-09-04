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

class ContSpec extends BaseSpec { outer =>

  def cont: IO[Unit] =
    IO.cont[Unit] flatMap { case (get, resume) =>
      resume(Right(()))
      get
    }

  // TODO move this to IOSpec. Generally review our use of `ticked` in IOSpec
  "async" in real {
    def execute(times: Int, i: Int = 0): IO[Boolean] =
        if (i == times) IO.pure(true)
        else cont >> execute(times, i + 1)

    execute(100000).flatMap { res =>
      IO {
        res must beTrue
      }
    }
  }
}
