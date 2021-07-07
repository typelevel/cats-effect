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

package cats.effect.std

import scala.concurrent.ExecutionContext

import scala.scalajs.js
import scala.scalajs.js.JSConverters._

private[std] trait DispatcherPlatform[F[_]] { this: Dispatcher[F] =>

  /**
   * Submits an effect to be executed, returning a `Promise` that holds the
   * result of its evaluation, along with a cancelation token that can be
   * used to cancel the original effect.
   */
  def unsafeToPromiseCancelable[A](fa: F[A]): (js.Promise[A], () => js.Promise[Unit]) = {
    val parasitic = new ExecutionContext {
      def execute(runnable: Runnable) = runnable.run()
      def reportFailure(t: Throwable) = t.printStackTrace()
    }

    val (f, cancel) = unsafeToFutureCancelable(fa)
    (f.toJSPromise(parasitic), () => cancel().toJSPromise(parasitic))
  }

  /**
   * Submits an effect to be executed, returning a `Promise` that holds the
   * result of its evaluation.
   */
  def unsafeToPromise[A](fa: F[A]): js.Promise[A] =
    unsafeToPromiseCancelable(fa)._1

  /**
   * Submits an effect to be executed, returning a cancelation token that
   * can be used to cancel it.
   */
  def unsafeRunCancelablePromise[A](fa: F[A]): () => js.Promise[Unit] =
    unsafeToPromiseCancelable(fa)._2

}
