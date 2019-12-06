/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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

package cats.effect.syntax

import cats.{Parallel, Traverse}

import scala.concurrent.duration.FiniteDuration
import cats.effect.{Concurrent, Timer}
import cats.effect.Resource

trait ConcurrentSyntax extends Concurrent.ToConcurrentOps {
  implicit def catsEffectSyntaxConcurrent[F[_], A](fa: F[A]): ConcurrentOps[F, A] =
    new ConcurrentOps[F, A](fa)

  implicit def catsEffectSyntaxConcurrentObj[F[_]](F: Concurrent[F]): ConcurrentObjOps[F] =
    new ConcurrentObjOps[F](F)
}

final class ConcurrentOps[F[_], A](val self: F[A]) extends AnyVal {
  def timeout(duration: FiniteDuration)(implicit F: Concurrent[F], timer: Timer[F]): F[A] =
    Concurrent.timeout[F, A](self, duration)

  def timeoutTo(duration: FiniteDuration, fallback: F[A])(implicit F: Concurrent[F], timer: Timer[F]): F[A] =
    Concurrent.timeoutTo(self, duration, fallback)

  /**
   * Returns a resource that will start execution of this effect in the background.
   *
   * In case the resource is closed while this effect is still running (e.g. due to a failure in `use`),
   * the background action will be canceled.
   *
   * A basic example with IO:
   *
   * {{{
   *   val longProcess = (IO.sleep(5.seconds) *> IO(println("Ping!"))).foreverM
   *
   *   val srv: Resource[IO, ServerBinding[IO]] = for {
   *     _ <- longProcess.background
   *     server <- server.run
   *   } yield server
   *
   *   val application = srv.use(binding => IO(println("Bound to " + binding)) *> IO.never)
   * }}}
   *
   * Here, we are starting a background process as part of the application's startup.
   * Afterwards, we initialize a server. Then, we use that server forever using `IO.never`.
   * This will ensure we never close the server resource unless somebody cancels the whole `application` action.
   *
   * If at some point of using the resource you want to wait for the result of the background action,
   * you can do so by sequencing the value inside the resource (it's equivalent to `join` on `Fiber`).
   *
   * This will start the background process, run another action, and wait for the result of the background process:
   *
   * {{{
   *   longProcess.background.use(await => anotherProcess *> await)
   * }}}
   *
   * In case the result of such an action is canceled, both processes will receive cancelation signals.
   * The same result can be achieved by using `anotherProcess &> longProcess` with the Parallel type class syntax.
   */
  def background(implicit F: Concurrent[F]): Resource[F, F[A]] = Resource.make(F.start(self))(_.cancel).map(_.join)
}

final class ConcurrentObjOps[F[_]](private val F: Concurrent[F]) extends AnyVal {
  /**
   * Like `Parallel.parTraverse`, but limits the degree of parallelism.
   */
  def parTraverseN[T[_], A, B](n: Long)(ta: T[A])(f: A => F[B])(implicit T: Traverse[T], P: Parallel[F]): F[T[B]] =
    Concurrent.parTraverseN(n)(ta)(f)(T, F, P)

  /**
   * Like `Parallel.parSequence`, but limits the degree of parallelism.
   */
  def parSequenceN[T[_], A](n: Long)(tma: T[F[A]])(implicit T: Traverse[T], P: Parallel[F]): F[T[A]] =
    Concurrent.parSequenceN(n)(tma)(T, F, P)
}
