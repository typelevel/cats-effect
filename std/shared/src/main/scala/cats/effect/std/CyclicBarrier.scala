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
 * A synchronization abstraction that allows a set of fibers
 * to wait until they all reach a certain point.
 *
 * A cyclic barrier is initialized with a positive integer capacity n and
 * a fiber waits by calling [[await]], at which point it is semantically
 * blocked until a total of n fibers are blocked on the same cyclic barrier.
 *
 * At this point all the fibers are unblocked and the cyclic barrier is reset,
 * allowing it to be used again.
 */
abstract class CyclicBarrier[F[_]] { self =>

  /**
   * Possibly semantically block until the cyclic barrier is full
   */
  def await: F[Unit]

  /**
   * Modifies the context in which this cyclic barrier is executed using the natural
   * transformation `f`.
   *
   * @return a cyclic barrier in the new context obtained by mapping the current one
   *         using the natural transformation `f`
   */
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
            case State(capacity, current, ids, signal) =>
              if (current < capacity - 1) {
                val id = new Proxy()
                val cleanup = state.update(s => {
                  val newIds = s.ids.filter(_ != id)
                  s.copy(current = newIds.size, ids = newIds)
                })
                (
                  State(capacity, current + 1, ids + id, signal),
                  poll(signal.get).onCancel(cleanup))
              } else (State(capacity, 0, Set.empty, newSignal), signal.complete(()).void)
          }.flatten
        }
      }

  }

  /**
   * Note that this is NOT a case class because we want reference equality
   * as a proxy for fiber identity
   */
  private[std] class Proxy()

  private[std] case class State[F[_]](
      capacity: Int,
      current: Int,
      ids: Set[Proxy],
      signal: Deferred[F, Unit])

  private[std] object State {
    def initial[F[_]](n: Int)(implicit F: GenConcurrent[F, _]): F[State[F]] =
      F.deferred[Unit].map { signal => State(n, 0, Set.empty, signal) }
  }
}
