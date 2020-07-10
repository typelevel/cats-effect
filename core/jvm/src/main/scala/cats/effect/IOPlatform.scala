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

package cats.effect

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

import java.util.concurrent.{CountDownLatch, CompletableFuture, TimeUnit}

private[effect] abstract class IOPlatform[+A] { self: IO[A] =>

  final def unsafeRunSync(implicit platform: unsafe.IOPlatform): A =
    unsafeRunTimed(Long.MaxValue.nanos).get

  final def unsafeRunTimed(limit: FiniteDuration)(implicit platform: unsafe.IOPlatform): Option[A] = {
    @volatile
    var results: Either[Throwable, A] = null
    val latch = new CountDownLatch(1)

    unsafeRunFiber(false) { e =>
      results = e
      latch.countDown()
    }

    if (latch.await(limit.toNanos, TimeUnit.NANOSECONDS)) {
      results.fold(
        throw _,
        a => Some(a))
    } else {
      None
    }
  }

  final def unsafeToCompletableFuture(implicit platform: unsafe.IOPlatform): CompletableFuture[A @uncheckedVariance] = {
    val cf = new CompletableFuture[A]()

    unsafeRunAsync {
      case Left(t) => cf.completeExceptionally(t)
      case Right(a) => cf.complete(a)
    }

    cf
  }
}
