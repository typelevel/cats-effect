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

import cats.effect.tracing.Tracing

import java.time.Instant
import java.util.concurrent.CompletableFuture

private[effect] abstract class IOCompanionPlatform { this: IO.type =>

  private[this] val TypeDelay = Sync.Type.Delay
  private[this] val TypeBlocking = Sync.Type.Blocking
  private[this] val TypeInterruptibleOnce = Sync.Type.InterruptibleOnce
  private[this] val TypeInterruptibleMany = Sync.Type.InterruptibleMany

  def blocking[A](thunk: => A): IO[A] = {
    val fn = Thunk.asFunction0(thunk)
    Blocking(TypeBlocking, fn, Tracing.calculateTracingEvent(fn.getClass))
  }

  def interruptible[A](many: Boolean)(thunk: => A): IO[A] = {
    val fn = Thunk.asFunction0(thunk)
    Blocking(
      if (many) TypeInterruptibleMany else TypeInterruptibleOnce,
      fn,
      Tracing.calculateTracingEvent(fn.getClass))
  }

  def interruptible[A](thunk: => A): IO[A] = interruptible(false)(thunk)

  def interruptibleMany[A](thunk: => A): IO[A] = interruptible(true)(thunk)

  def suspend[A](hint: Sync.Type)(thunk: => A): IO[A] =
    if (hint eq TypeDelay)
      apply(thunk)
    else {
      val fn = () => thunk
      Blocking(hint, fn, Tracing.calculateTracingEvent(fn.getClass))
    }

  def fromCompletableFuture[A](fut: IO[CompletableFuture[A]]): IO[A] =
    asyncForIO.fromCompletableFuture(fut)

  def realTimeInstant: IO[Instant] = asyncForIO.realTimeInstant
}
