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

package cats.effect.kernel

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

private[kernel] trait AsyncPlatform[F[_]] { this: Async[F] =>

  /**
   * Suspend a [[java.util.concurrent.CompletableFuture]] into the `F[_]`
   * context.
   *
   * @param fut The [[java.util.concurrent.CompletableFuture]] to
   * suspend in `F[_]`
   */
  def fromCompletableFuture[A](fut: F[CompletableFuture[A]]): F[A] =
    flatMap(fut) { cf =>
      async[A] { cb =>
        delay {
          val stage = cf.handle[Unit] {
            case (a, null) => cb(Right(a))
            case (_, t) =>
              cb(Left(t match {
                case e: CompletionException if e.getCause ne null => e.getCause
                case _ => t
              }))
          }

          Some(void(delay(stage.cancel(false))))
        }
      }
    }
}
