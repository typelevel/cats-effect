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

import cats.effect.IO
import scala.annotation.implicitNotFound

@implicitNotFound("""Cannot find implicit value for ConcurrentEffect[${F}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Scheduler or some equivalent type.""")
trait ConcurrentEffect[F[_], E] extends Concurrent[F, E] with Effect[F, E] {
  /**
   * Evaluates `F[_]` with the ability to cancel it.
   *
   * The returned `IO[IO[Unit]]` is a suspended cancelable action that
   * can be used to cancel the running computation.
   *
   * Note that evaluating the returned `IO` value, along with
   * the boxed cancelable action are guaranteed to have immediate
   * (synchronous) execution so you can safely do this, even
   * on top of JavaScript (which has no ability to block threads):
   *
   * {{{
   *   val io = F.runCancelable(fa)(cb)
   *
   *   // For triggering asynchronous execution
   *   val cancel = io.unsafeRunSync
   *   // For cancellation
   *   cancel.unsafeRunSync
   * }}}
   */
  def runCancelable[A](fa: F[A])(cb: Either[E, A] => IO[Unit]): IO[IO[Unit]]
}
