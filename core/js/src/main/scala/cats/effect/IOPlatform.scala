/*
 * Copyright 2020-2021 Typelevel
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
import scala.scalajs.js.{|, Function1, JavaScriptException, Promise, Thenable}

abstract private[effect] class IOPlatform[+A] { self: IO[A] =>

  def unsafeToPromise()(implicit runtime: unsafe.IORuntime): Promise[A] =
    new Promise[A]((resolve: Function1[A | Thenable[A], _], reject: Function1[Any, _]) =>
      self.unsafeRunAsync {
        case Left(JavaScriptException(e)) =>
          reject(e)
          ()

        case Left(e) =>
          reject(e)
          ()

        case Right(value) =>
          resolve(value)
          ()
      })

  def unsafeRunSyncToFuture()(implicit runtime: unsafe.IORuntime): Future[A] =
    self.syncStep(runtime.config.autoYieldThreshold).attempt.unsafeRunSync() match {
      case Left(t) => Future.failed(t)
      case Right(Left(ioa)) => ioa.unsafeToFuture()
      case Right(Right(a)) => Future.successful(a)
    }

  def unsafeRunSyncToPromise()(implicit runtime: unsafe.IORuntime): Promise[A] =
    self.syncStep(runtime.config.autoYieldThreshold).attempt.unsafeRunSync() match {
      case Left(t) => Promise.reject(t)
      case Right(Left(ioa)) => ioa.unsafeToPromise()
      case Right(Right(a)) => Promise.resolve[A](a)
    }
}
