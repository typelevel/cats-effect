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

package cats.effect

import scala.concurrent.Future
import scala.scalajs.js

abstract private[effect] class IOPlatform[+A] { self: IO[A] =>

  /**
   * Evaluates the effect and produces the result in a JavaScript `Promise`.
   *
   * This is similar to `unsafeRunAsync` in that it evaluates the `IO` as a side effect in a
   * non-blocking fashion, but uses a `Promise` rather than an explicit callback. This function
   * should really only be used if interoperating with code which uses JavaScript promises.
   *
   * @see
   *   [[IO.fromPromise]]
   */
  def unsafeToPromise()(implicit runtime: unsafe.IORuntime): js.Promise[A] =
    new js.Promise[A]((resolve, reject) =>
      self.unsafeRunAsync {
        case Left(js.JavaScriptException(e)) =>
          reject(e)
          ()

        case Left(e) =>
          reject(e)
          ()

        case Right(value) =>
          resolve(value)
          ()
      })

  /**
   * Evaluates the effect and produces the result in a `Future`.
   *
   * This is similar to `unsafeToFuture` in that it evaluates the `IO` as a side effect in a
   * non-blocking fashion, but begins by taking a `syncStep` limited by the runtime's auto-yield
   * threshold. This function should really only be used if it is critical to attempt to
   * evaluate this `IO` without first yielding to the event loop.
   *
   * @see
   *   [[IO.syncStep(limit:Int)*]]
   */
  def unsafeRunSyncToFuture()(implicit runtime: unsafe.IORuntime): Future[A] =
    self.syncStep(runtime.config.autoYieldThreshold).attempt.unsafeRunSync() match {
      case Left(t) => Future.failed(t)
      case Right(Left(ioa)) => ioa.unsafeToFuture()
      case Right(Right(a)) => Future.successful(a)
    }

  /**
   * Evaluates the effect and produces the result in a JavaScript `Promise`.
   *
   * This is similar to `unsafeToPromise` in that it evaluates the `IO` as a side effect in a
   * non-blocking fashion, but begins by taking a `syncStep` limited by the runtime's auto-yield
   * threshold. This function should really only be used if it is critical to attempt to
   * evaluate this `IO` without first yielding to the event loop.
   *
   * @see
   *   [[IO.syncStep(limit:Int)*]]
   */
  def unsafeRunSyncToPromise()(implicit runtime: unsafe.IORuntime): js.Promise[A] =
    self.syncStep(runtime.config.autoYieldThreshold).attempt.unsafeRunSync() match {
      case Left(t) => js.Promise.reject(t)
      case Right(Left(ioa)) => ioa.unsafeToPromise()
      case Right(Right(a)) => js.Promise.resolve[A](a)
    }
}
