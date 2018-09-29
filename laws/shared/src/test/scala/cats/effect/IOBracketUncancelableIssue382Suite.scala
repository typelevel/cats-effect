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

import cats.implicits._
import scala.concurrent.Promise
import scala.util.Success

/** 
 * This test suite is to make sure that `IO` overrides `uncancelable` in
 * both `Effect` and `ConcurrentEffect`.
 *
 * See issue: https://github.com/typelevel/cats-effect/issues/382
 *
 * TODO: remove in Cats-Effect 2.0
 */
class IOBracketUncancelableIssue382Suite extends BaseTestsSuite {
  testAsync("ConcurrentEffect[IO].uncancelable should not fork") { ec =>
    implicit val contextShift = ec.contextShift[IO]
    val F = ConcurrentEffect[IO]
    val io = F.uncancelable(IO.cancelBoundary *> IO(1))
    val p = Promise[Int]()

    io.unsafeRunCancelable {
      case Left(e) => p.failure(e)
      case Right(a) => p.success(a)
    }

    p.future.value shouldBe Some(Success(1))
  }

  testAsync("Effect[IO].uncancelable should work") { ec =>
    val F = Effect[IO]
    val contextShift = ec.contextShift[IO]
    val io = F.uncancelable(contextShift.shift *> IO(1))
    val p = Promise[Int]()

    val c = io.unsafeRunCancelable {
      case Left(e) => p.failure(e)
      case Right(a) => p.success(a)
    }

    c.unsafeRunAsyncAndForget
    ec.tick()

    p.future.value shouldBe Some(Success(1))
    ec.state.tasks.isEmpty shouldBe true    
  }

  testAsync("ConcurrentEffect[IO].uncancelable should work") { ec =>
    implicit val contextShift = ec.contextShift[IO]
    val F = ConcurrentEffect[IO]
    val io = F.uncancelable(contextShift.shift *> IO(1))
    val p = Promise[Int]()

    val c = io.unsafeRunCancelable {
      case Left(e) => p.failure(e)
      case Right(a) => p.success(a)
    }

    c.unsafeRunAsyncAndForget
    ec.tickOne()

    p.future.value shouldBe Some(Success(1))
    ec.state.tasks.isEmpty shouldBe true    
  }
}
