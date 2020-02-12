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
import cats.effect.internals.{IOResourceAppPlatform, TestUtils, TrampolineEC}
import org.scalatest.matchers.should.Matchers
import org.scalatest.funsuite.AsyncFunSuite

class IOResourceAppTests extends AsyncFunSuite with Matchers with TestUtils {
  test("exits with specified code") {
    IOResourceAppPlatform
      .mainProcess(Array.empty, Eval.now(implicitly[ContextShift[IO]]), Eval.now(implicitly[Timer[IO]]))(
        _ => Resource.pure[IO, ExitCode](ExitCode(42))
      )
      .unsafeToFuture()
      .map(_.code shouldEqual 42)
  }

  test("accepts arguments") {
    IOResourceAppPlatform
      .mainProcess(Array("1", "2", "3"), Eval.now(implicitly), Eval.now(implicitly))(
        args => Resource.pure[IO, ExitCode](ExitCode(args.mkString.toInt))
      )
      .unsafeToFuture()
      .map(_.code shouldEqual 123)
  }

  test("raised error exits with 1") {
    silenceSystemErr {
      IOResourceAppPlatform
        .mainProcess(Array.empty, Eval.now(implicitly), Eval.now(implicitly))(
          _ => Resource.liftF(IO.raiseError(new Exception()))
        )
        .unsafeToFuture()
        .map(_.code shouldEqual 1)
    }
  }

  implicit override def executionContext: ExecutionContext = TrampolineEC.immediate
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)
}
