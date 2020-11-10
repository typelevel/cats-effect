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

package cats.effect.std

import cats.~>
import cats.effect.kernel.{Deferred, GenConcurrent, Ref}
import cats.effect.kernel.syntax.all._
import cats.syntax.all._

/**
 */
abstract class CyclicBarrier[F[_]] { self =>

  def await: F[Unit]

  def mapK[G[_]](f: F ~> G): CyclicBarrier[G] =
    new CyclicBarrier[G] {
      def await: G[Unit] = f(self.await)
    }

}

object CyclicBarrier {
  def apply[F[_]](n: Int)(implicit F: GenConcurrent[F, _]): F[CyclicBarrier[F]] =
    if (n < 1)
      throw new IllegalArgumentException(
        s"Cyclic barrier constructed with capacity $n. Must be > 0")
    else
      for {
        state <- State.initial[F](n)
        ref <- F.ref(state)
      } yield new ConcurrentCyclicBarrier(ref)

  private[std] class ConcurrentCyclicBarrier[F[_]](state: Ref[F, State[F]])(
      implicit F: GenConcurrent[F, _])
      extends CyclicBarrier[F] {

    def await: F[Unit] =
      F.deferred[Unit].flatMap { newSignal =>
        F.uncancelable { poll =>
          state.modify {
            case State(capacity, current, signal) =>
              if (current < capacity - 1) {
                val cleanup = state.update(s => s.copy(current = s.current - 1))
                (State(capacity, current + 1, signal), poll(signal.get).onCancel(cleanup))
              } else (State(capacity, 0, newSignal), signal.complete(()).void)
          }.flatten
        }
      }

  }

  private[std] case class State[F[_]](capacity: Int, current: Int, signal: Deferred[F, Unit])

  private[std] object State {
    def initial[F[_]](n: Int)(implicit F: GenConcurrent[F, _]): F[State[F]] =
      F.deferred[Unit].map { signal => new State(n, 0, signal) }
  }
}
