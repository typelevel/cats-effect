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

import cats.effect.std.Console
import cats.effect.tracing.Tracing

import java.time.Instant
import java.util.concurrent.{CompletableFuture, CompletionStage}

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
   * use [[interruptible[A](thunk:=>A):*]] or [[interruptibleMany]].
   *
   * {{{
   * IO.blocking(scala.io.Source.fromFile("path").mkString)
   * }}}
   *
   * @param thunk
   *   The side effect which is to be suspended in `IO` and evaluated on a blocking execution
   *   context
   *
   * Implements [[cats.effect.kernel.Sync.blocking]].
   */
  def blocking[A](thunk: => A): IO[A] = {
    val fn = () => thunk
    Blocking(TypeBlocking, fn, Tracing.calculateTracingEvent(fn.getClass))
  }

  // this cannot be marked private[effect] because of static forwarders in Java
  @deprecated("use interruptible / interruptibleMany instead", "3.3.0")
  def interruptible[A](many: Boolean, thunk: => A): IO[A] = {
    val fn = () => thunk
    Blocking(
      if (many) TypeInterruptibleMany else TypeInterruptibleOnce,
      fn,
      Tracing.calculateTracingEvent(fn.getClass))
  }

  /**
   * Like [[blocking]] but will attempt to abort the blocking operation using thread interrupts
   * in the event of cancelation. The interrupt will be attempted only once.
   *
   * Note the following tradeoffs:
   *   - this has slightly more overhead than [[blocking]] due to the machinery necessary for
   *     the interrupt coordination,
   *   - thread interrupts are very often poorly considered by Java (and Scala!) library
   *     authors, and it is possible for interrupts to result in resource leaks or invalid
   *     states. It is important to be certain that this will not be the case before using this
   *     mechanism.
   *
   * @param thunk
   *   The side effect which is to be suspended in `IO` and evaluated on a blocking execution
   *   context
   *
   * Implements [[cats.effect.kernel.Sync.interruptible[A](thunk:=>A):*]]
   */
  def interruptible[A](thunk: => A): IO[A] = {
    val fn = () => thunk
    Blocking(TypeInterruptibleOnce, fn, Tracing.calculateTracingEvent(fn.getClass))
  }

  /**
   * Like [[blocking]] but will attempt to abort the blocking operation using thread interrupts
   * in the event of cancelation. The interrupt will be attempted repeatedly until the blocking
   * operation completes or exits.
   *
   * Note the following tradeoffs:
   *   - this has slightly more overhead than [[blocking]] due to the machinery necessary for
   *     the interrupt coordination,
   *   - thread interrupts are very often poorly considered by Java (and Scala!) library
   *     authors, and it is possible for interrupts to result in resource leaks or invalid
   *     states. It is important to be certain that this will not be the case before using this
   *     mechanism.
   *
   * @param thunk
   *   The side effect which is to be suspended in `IO` and evaluated on a blocking execution
   *   context
   *
   * Implements [[cats.effect.kernel.Sync!.interruptibleMany]]
   */
  def interruptibleMany[A](thunk: => A): IO[A] = {
    val fn = () => thunk
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

  def fromCompletionStage[A](completionStage: IO[CompletionStage[A]]): IO[A] =
    asyncForIO.fromCompletionStage(completionStage)

  def realTimeInstant: IO[Instant] = asyncForIO.realTimeInstant

  /**
   * Reads a line as a string from the standard input using the platform's default charset, as
   * per `java.nio.charset.Charset.defaultCharset()`.
   *
   * The effect can raise a `java.io.EOFException` if no input has been consumed before the EOF
   * is observed. This should never happen with the standard input, unless it has been replaced
   * with a finite `java.io.InputStream` through `java.lang.System#setIn` or similar.
   *
   * @see
   *   `cats.effect.std.Console#readLineWithCharset` for reading using a custom
   *   `java.nio.charset.Charset`
   *
   * @return
   *   an IO effect that describes reading the user's input from the standard input as a string
   */
  def readLine: IO[String] =
    Console[IO].readLine

}
