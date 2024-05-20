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

package cats.effect.syntax

import cats.effect.SyncIO
import cats.effect.kernel.Async
import cats.effect.std.Dispatcher

import scala.concurrent.Future
import scala.scalajs.js.Promise

trait DispatcherSyntax {
  implicit def dispatcherOps[F[_]](wrapped: Dispatcher[F]): DispatcherOps[F] =
    new DispatcherOps(wrapped)
}

final class DispatcherOps[F[_]] private[syntax] (private[syntax] val wrapped: Dispatcher[F])
    extends AnyVal {

  /**
   * Executes an effect by first taking a `syncStep` limited by `syncLimit`, then if necessary
   * submitting the remainder of the effect to the dispatcher to be executed asynchronously,
   * finally returning a `Future` that holds the result of its evaluation.
   *
   * This function should really only be used if it is critical to attempt to evaluate this
   * effect without first yielding to the event loop.
   *
   * @see
   *   [[cats.effect.kernel.Async.syncStep]]
   */
  def unsafeRunSyncToFuture[A](fa: F[A], syncLimit: Int)(implicit F: Async[F]): Future[A] =
    F.syncStep[SyncIO, A](fa, syncLimit).attempt.unsafeRunSync() match {
      case Left(t) => Future.failed(t)
      case Right(Left(fa1)) => wrapped.unsafeToFuture(fa1)
      case Right(Right(a)) => Future.successful(a)
    }

  /**
   * Executes an effect by first taking a `syncStep` limited by `syncLimit`, then if necessary
   * submitting the remainder of the effect to the dispatcher to be executed asynchronously,
   * finally returning a `Promise` that holds the result of its evaluation.
   *
   * This function should really only be used if it is critical to attempt to evaluate this
   * effect without first yielding to the event loop.
   *
   * @see
   *   [[cats.effect.kernel.Async.syncStep]]
   */
  def unsafeRunSyncToPromise[A](fa: F[A], syncLimit: Int)(implicit F: Async[F]): Promise[A] =
    F.syncStep[SyncIO, A](fa, syncLimit).attempt.unsafeRunSync() match {
      case Left(t) => Promise.reject(t)
      case Right(Left(fa1)) => wrapped.unsafeToPromise(fa1)
      case Right(Right(a)) => Promise.resolve[A](a)
    }

}
