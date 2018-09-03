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

package cats
package effect

import cats.effect.internals.{IOAppPlatform, TestUtils, TrampolineEC}
import org.scalatest.{AsyncFunSuite, BeforeAndAfterAll, Matchers}

class IOAppTests extends AsyncFunSuite with Matchers with BeforeAndAfterAll with TestUtils {
  val trampoline = TrampolineEC.immediate

  test("exits with specified code") {
    IOAppPlatform.main(Array.empty, trampoline) { (_, _) =>
      IO.pure(ExitCode(42))
    }.unsafeToFuture.map(_ shouldEqual 42)
  }

  test("accepts arguments") {
    IOAppPlatform.main(Array("1", "2", "3"), trampoline) { (_, args) =>
      IO.pure(ExitCode(args.mkString.toInt))
    }.unsafeToFuture.map(_ shouldEqual 123)
  }

  test("raised error exits with 1") {
    silenceSystemErr {
      IOAppPlatform.main(Array.empty, trampoline) { (_, _) =>
        IO.raiseError(new Exception())
      }.unsafeToFuture.map(_ shouldEqual 1)
    }
  }
}
