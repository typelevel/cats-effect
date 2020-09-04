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
  sequential

  // TODO move this to IOSpec. Generally review our use of `ticked` in IOSpec
  "async" in real {
    def execute(times: Int): IO[Boolean] = {
      def foreverAsync(i: Int): IO[Unit] =
        if (i == times) IO.unit
        else {
          IO.async[Unit] { cb =>
            cb(Right(()))
            IO.pure(None)
          } >> foreverAsync(i + 1)
        }

      foreverAsync(0).as(true)
    }

    execute(100000).flatMap { res =>
      IO {
        res must beTrue
      }
    }
  }
}
