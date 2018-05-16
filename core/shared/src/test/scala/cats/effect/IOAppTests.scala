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

import cats.effect.internals.{IOAppPlatform, IOPlatform, TestUtils}
import cats.implicits._
import org.scalatest.{AsyncFunSuite, BeforeAndAfterAll, Matchers}
import scala.util.Success

class IOAppTests extends AsyncFunSuite with Matchers with BeforeAndAfterAll with TestUtils {
  test("exits with specified code") {
    IOAppPlatform.mainFiber(Array.empty)(_ => IO.pure(ExitCode(42)))
      .flatMap(_.join)
      .unsafeToFuture
      .value shouldEqual (Some(Success(42)))
  }

  test("accepts arguments") {
    IOAppPlatform.mainFiber(Array("1", "2", "3"))(args => IO.pure(ExitCode(args.mkString.toInt)))
      .flatMap(_.join)
      .unsafeToFuture
      .value shouldEqual (Some(Success(123)))
  }

  test("raised error exits with 1") {
    silenceSystemErr {
      IOAppPlatform.mainFiber(Array.empty)(_ => IO.raiseError(new Exception()))
        .flatMap(_.join)
        .unsafeToFuture
        .value shouldEqual (Some(Success(1)))
    }
  }

  test("canceled IO exits unsuccessfully") {
    assume(IOPlatform.isJVM, "test relevant only for the JVM")
    silenceSystemErr {
      (for {
        fiber <- IOAppPlatform.mainFiber(Array.empty)(_ => IO.never)
        _ <- fiber.cancel
        code <- fiber.join
      } yield code)
        .unsafeToFuture
        .value
        .getOrElse(Success(0))
        .getOrElse(0) should be > 0
    }
  }
}
