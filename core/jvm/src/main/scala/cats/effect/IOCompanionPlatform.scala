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

import java.util.concurrent.CompletableFuture

private[effect] abstract class IOCompanionPlatform { this: IO.type =>

  private[this] val TypeDelay = Sync.Type.Delay
  private[this] val TypeBlocking = Sync.Type.Blocking
  private[this] val TypeInterruptibleOnce = Sync.Type.InterruptibleOnce
  private[this] val TypeInterruptibleMany = Sync.Type.InterruptibleMany

  def blocking[A](thunk: => A): IO[A] =
    Blocking(TypeBlocking, () => thunk)

  def interruptible[A](many: Boolean)(thunk: => A): IO[A] =
    Blocking(if (many) TypeInterruptibleMany else TypeInterruptibleOnce, () => thunk)

  def suspend[A](hint: Sync.Type)(thunk: => A): IO[A] =
    if (hint eq TypeDelay)
      apply(thunk)
    else
      Blocking(hint, () => thunk)

  // TODO deduplicate with AsyncPlatform#fromCompletableFuture (requires Dotty 0.26 or higher)
  def fromCompletableFuture[A](fut: IO[CompletableFuture[A]]): IO[A] =
    fut flatMap { cf =>
      async[A] { cb =>
        IO {
          val stage = cf.handle[Unit] {
            case (a, null) => cb(Right(a))
            case (_, t) => cb(Left(t))
          }

          Some(IO(stage.cancel(false)).void)
        }
      }
    }
}
