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

package cats.effect.kernel

import java.util.concurrent.CompletableFuture

private[kernel] abstract class AsyncPlatform[F[_]] { this: Async[F] =>

  def fromCompletableFuture[A](fut: F[CompletableFuture[A]]): F[A] =
    flatMap(fut) { cf =>
      async[A] { cb =>
        delay {
          val stage = cf.handle[Unit] {
            case (a, null) => cb(Right(a))
            case (_, t) => cb(Left(t))
          }

          Some(void(delay(stage.cancel(false))))
        }
      }
    }
}
