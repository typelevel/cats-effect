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

import scala.concurrent.ExecutionContext
import cats.effect.internals.{IOAppPlatform, TestUtils, TrampolineEC}
import org.scalatest.matchers.should.Matchers
import org.scalatest.funsuite.AsyncFunSuite

class IOAppTests extends AsyncFunSuite with Matchers with TestUtils {
  test("exits with specified code") {
    IOAppPlatform
      .mainFiber(Array.empty, Eval.now(implicitly[ContextShift[IO]]), Eval.now(implicitly[Timer[IO]]))(_ =>
        IO.pure(ExitCode(42))
      )
      .flatMap(_.join)
      .unsafeToFuture()
      .map(_ shouldEqual 42)
  }

  test("accepts arguments") {
    IOAppPlatform
      .mainFiber(Array("1", "2", "3"), Eval.now(implicitly), Eval.now(implicitly))(args =>
        IO.pure(ExitCode(args.mkString.toInt))
      )
      .flatMap(_.join)
      .unsafeToFuture()
      .map(_ shouldEqual 123)
  }

  test("raised error exits with 1") {
    silenceSystemErr {
      IOAppPlatform
        .mainFiber(Array.empty, Eval.now(implicitly), Eval.now(implicitly))(_ => IO.raiseError(new Exception()))
        .flatMap(_.join)
        .unsafeToFuture()
        .map(_ shouldEqual 1)
    }
  }

  implicit override def executionContext: ExecutionContext = TrampolineEC.immediate
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)
}
