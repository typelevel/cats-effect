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

import cats.syntax.all._

/**
 * A datatype that represents a handle to a fiber and allows for waiting and
 * cancellation against that fiber.
 *
 * @see [[GenSpawn]] documentation for more detailed information on the
 * concurrency of fibers.
 */
trait Fiber[F[_], E, A] {

  /**
   * Requests the cancellation of the fiber bound to this `Fiber` handle
   * and awaits its finalization.
   *
   * [[cancel]] semantically blocks the caller until finalization of the
   * cancellee has completed. This means that if the cancellee is currently
   * masked, [[cancel]] will block until it is unmasked and finalized.
   *
   * Cancellation is idempotent, so repeated calls to [[cancel]] simply block
   * until finalization is complete. If [[cancel]] is called after finalization
   * is complete, it will return immediately.
   *
   * [[cancel]] is uncancelable; a fiber that is canceling another fiber
   * is masked from cancellation.
   *
   * @see [[GenSpawn]] documentation for more details on cancellation
   */
  def cancel: F[Unit]

  /**
   * Awaits the completion of the fiber bound to this [[Fiber]] and returns
   * its [[Outcome]] once it completes.
   */
  def join: F[Outcome[F, E, A]]

  /**
   * Awaits the completion of the bound fiber and returns its result once
   * it completes.
   *
   * If the fiber completes with [[Succeeded]], the successful value is
   * returned. If the fiber completes with [[Errored]], the error is raised.
   * If the fiber completes with [[Cancelled]], `onCancel` is run.
   */
  def joinWith(onCancel: F[A])(implicit F: MonadCancel[F, E]): F[A] =
    join.flatMap(_.embed(onCancel))

  /**
   * Awaits the completion of the bound fiber and returns its result once
   * it completes.
   *
   * If the fiber completes with [[Succeeded]], the successful value is
   * returned. If the fiber completes with [[Errored]], the error is raised.
   * If the fiber completes with [[Cancelled]], the caller is indefinitely
   * suspended without termination.
   */
  def joinWithNever(implicit F: GenSpawn[F, E]): F[A] =
    joinWith(F.never)
}
