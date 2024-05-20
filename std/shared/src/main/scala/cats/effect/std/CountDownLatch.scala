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

package cats.effect.std

import cats.effect.kernel.{Deferred, GenConcurrent, Ref}
import cats.syntax.all._
import cats.~>

/**
 * Concurrency abstraction that supports fiber blocking until n latches are released. Note that
 * this has 'one-shot' semantics - once the counter reaches 0 then [[release]] and [[await]]
 * will forever be no-ops
 *
 * See https://typelevel.org/blog/2020/10/30/concurrency-in-ce3.html for a walkthrough of
 * building something like this
 */
abstract class CountDownLatch[F[_]] { self =>

  /**
   * Release a latch, decrementing the remaining count and releasing any fibers that are blocked
   * if the count reaches 0
   */
  def release: F[Unit]

  /**
   * Semantically block until the count reaches 0
   */
  def await: F[Unit]

  def mapK[G[_]](f: F ~> G): CountDownLatch[G] =
    new CountDownLatch[G] {
      def release: G[Unit] = f(self.release)
      def await: G[Unit] = f(self.await)
    }

}

object CountDownLatch {

  /**
   * Initialize a CountDown latch with n latches
   */
  def apply[F[_]](n: Int)(implicit F: GenConcurrent[F, _]): F[CountDownLatch[F]] =
    if (n < 1)
      throw new IllegalArgumentException(
        s"Initialized with $n latches. Number of latches must be > 0")
    else
      for {
        state <- State.initial[F](n)
        ref <- F.ref(state)
      } yield new ConcurrentCountDownLatch[F](ref)

  private[std] class ConcurrentCountDownLatch[F[_]](state: Ref[F, State[F]])(
      implicit F: GenConcurrent[F, _])
      extends CountDownLatch[F] {

    override def release: F[Unit] =
      state.flatModify {
        case Awaiting(n, signal) =>
          if (n > 1) (Awaiting(n - 1, signal), F.unit) else (Done(), signal.complete(()).void)
        case d @ Done() => (d, F.unit)
      }

    override def await: F[Unit] =
      state.get.flatMap {
        case Awaiting(_, signal) => signal.get
        case Done() => F.unit
      }

  }

  private[std] sealed trait State[F[_]]
  private[std] case class Awaiting[F[_]](latches: Int, signal: Deferred[F, Unit])
      extends State[F]
  private[std] case class Done[F[_]]() extends State[F]

  private[std] object State {
    def initial[F[_]](n: Int)(implicit F: GenConcurrent[F, _]): F[State[F]] =
      F.deferred[Unit].map { signal => Awaiting(n, signal) }
  }

}
