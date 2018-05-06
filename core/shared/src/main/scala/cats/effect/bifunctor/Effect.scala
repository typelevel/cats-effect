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

package cats.effect.bifunctor

import scala.annotation.implicitNotFound
import cats.effect.IO

@implicitNotFound("""Cannot find implicit value for Effect[${F}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Scheduler or some equivalent type.""")
trait Effect[F[_], E] extends Async[F, E] {

  /**
   * Evaluates `F[_]`, with the effect of starting the run-loop
   * being suspended in the `IO` context.
   *
   * Note that evaluating the returned `IO[Unit]` is guaranteed
   * to execute immediately:
   * {{{
   *   val io = F.runAsync(fa)(cb)
   *
   *   // For triggering actual execution, guaranteed to be
   *   // immediate because it doesn't wait for the result
   *   io.unsafeRunSync
   * }}}
   */
  def runAsync[A](fa: F[A])(cb: Either[E, A] => IO[Unit]): IO[Unit]

  def runSyncStep[A](fa: F[A]): IO[Either[F[A], A]]
}
