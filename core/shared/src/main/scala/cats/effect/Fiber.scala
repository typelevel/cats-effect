/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

/**
 * `Fiber` represents the (pure) result of an [[Async]] data type (e.g. [[IO]])
 * being started concurrently and that can be either joined or cancelled.
 *
 * You can think of fibers as being lightweight threads, a fiber being a
 * concurrency primitive for doing cooperative multi-tasking.
 *
 * For example a `Fiber` value is the result of evaluating [[IO.start]]:
 *
 * {{{
 *   val io = IO.shift *> IO(println("Hello!"))
 *
 *   val fiber: IO[Fiber[IO, Unit]] = io.start
 * }}}
 *
 * Usage example:
 *
 * {{{
 *   for {
 *     fiber <- IO.shift *> launchMissiles.start
 *     _ <- runToBunker.handleErrorWith { error =>
 *       // Retreat failed, cancel launch (maybe we should
 *       // have retreated to our bunker before the launch?)
 *       fiber.cancel *> IO.raiseError(error)
 *     }
 *     aftermath <- fiber.join
 *   } yield {
 *     aftermath
 *   }
 * }}}
 */
trait Fiber[F[_], A] {
  /**
   * Triggers the cancellation of the fiber.
   *
   * Returns a new task that will complete when the cancellation is
   * sent (but not when it is observed or acted upon).
   *
   * Note that if the background process that's evaluating the result
   * of the underlying fiber is already complete, then there's nothing
   * to cancel.
   */
  def cancel: F[Unit]

  /**
   * Returns a new task that will await for the completion of the
   * underlying fiber, (asynchronously) blocking the current run-loop
   * until that result is available.
   */
  def join: F[A]
}
