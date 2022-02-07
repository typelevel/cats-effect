/*
 * Copyright 2020-2022 Typelevel
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

  /**
   * Intended for thread blocking operations. `blocking` will shift the execution of the
   * blocking operation to a separate threadpool to avoid blocking on the main execution
   * context. See the thread-model documentation for more information on why this is necessary.
   * Note that the created effect will be uncancelable; if you need cancelation then you should
   * use [[interruptible]] or [[interruptibleMany]].
   *
   * {{{
   * IO.blocking(scala.io.Source.fromFile("path").mkString)
   * }}}
   *
   * @param thunk
   *   The side effect which is to be suspended in `IO` and evaluated on a blocking execution
   *   context
   *
   * Implements [[Sync#blocking]].
   */
  def blocking[A](thunk: => A): IO[A] = {
    val fn = Thunk.asFunction0(thunk)
    Blocking(TypeBlocking, fn, Tracing.calculateTracingEvent(fn.getClass))
  }

  // this cannot be marked private[effect] because of static forwarders in Java
  @deprecated("use interruptible / interruptibleMany instead", "3.3.0")
  def interruptible[A](many: Boolean, thunk: => A): IO[A] = {
    val fn = Thunk.asFunction0(thunk)
    Blocking(
      if (many) TypeInterruptibleMany else TypeInterruptibleOnce,
      fn,
      Tracing.calculateTracingEvent(fn.getClass))
  }

  /**
   * Like [[blocking]] but will attempt to abort the blocking operation using thread interrupts
   * in the event of cancellation. The interrupt will be attempted only once.
   *
   * @param thunk
   *   The side effect which is to be suspended in `IO` and evaluated on a blocking execution
   *   context
   *
   * Implements [[Sync#interruptible]]
   */
  def interruptible[A](thunk: => A): IO[A] = {
    val fn = Thunk.asFunction0(thunk)
    Blocking(TypeInterruptibleOnce, fn, Tracing.calculateTracingEvent(fn.getClass))
  }

  /**
   * Like [[blocking]] but will attempt to abort the blocking operation using thread interrupts
   * in the event of cancelation. The interrupt will be attempted repeatedly until the blocking
   * operation completes or exits.
   *
   * @param thunk
   *   The side effect which is to be suspended in `IO` and evaluated on a blocking execution
   *   context
   *
   * Implements [[Sync#interruptibleMany]]
   */
  def interruptibleMany[A](thunk: => A): IO[A] = {
    val fn = Thunk.asFunction0(thunk)
    Blocking(TypeInterruptibleMany, fn, Tracing.calculateTracingEvent(fn.getClass))
  }

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
