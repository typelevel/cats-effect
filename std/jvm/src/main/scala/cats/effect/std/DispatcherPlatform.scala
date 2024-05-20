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

package cats.effect.std

import scala.concurrent.{Await, TimeoutException}
import scala.concurrent.duration.Duration

import java.util.concurrent.CompletableFuture

private[std] trait DispatcherPlatform[F[_]] { this: Dispatcher[F] =>

  /**
   * Submits an effect to be executed, returning a `CompletableFuture` that holds the result of
   * its evaluation.
   */
  def unsafeToCompletableFuture[A](fa: F[A]): CompletableFuture[A] = {
    val cf = new CompletableFuture[A]()

    unsafeRunAsync(fa) {
      case Left(t) =>
        cf.completeExceptionally(t)
        ()

      case Right(a) =>
        cf.complete(a)
        ()
    }

    cf
  }

  /**
   * Submits an effect to be executed and indefinitely blocks until a result is produced. This
   * function will throw an exception if the submitted effect terminates with an error.
   */
  def unsafeRunSync[A](fa: F[A]): A =
    unsafeRunTimed(fa, Duration.Inf)

  /**
   * Submits an effect to be executed and blocks for at most the specified timeout for a result
   * to be produced. This function will throw an exception if the submitted effect terminates
   * with an error.
   */
  def unsafeRunTimed[A](fa: F[A], timeout: Duration): A = {
    val (fut, cancel) = unsafeToFutureCancelable(fa)
    try Await.result(fut, timeout)
    catch {
      case t: TimeoutException =>
        cancel()
        throw t
    }
  }
}
