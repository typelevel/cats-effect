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

  /*
   * The number of fibers required to trip the barrier
   */
  def remaining: F[Int]

  /*
   * The number of fibers currently awaiting
   */
  def awaiting: F[Int]

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
      def remaining: G[Int] = f(self.remaining)
      def awaiting: G[Int] = f(self.awaiting)
    }

}

object CyclicBarrier {
  def apply[F[_]](n: Int)(implicit F: GenConcurrent[F, _]): F[CyclicBarrier[F]] =
    if (n < 1)
      throw new IllegalArgumentException(
        s"Cyclic barrier constructed with capacity $n. Must be > 0")
    else
      for {
        state <- State.initial[F]
        ref <- F.ref(state)
      } yield new ConcurrentCyclicBarrier(n, ref)

  private[std] class ConcurrentCyclicBarrier[F[_]](capacity: Int, state: Ref[F, State[F]])(
      implicit F: GenConcurrent[F, _])
      extends CyclicBarrier[F] {

    val await: F[Unit] =
      F.deferred[Unit].flatMap { newSignal =>
        F.uncancelable { poll =>
          state.modify {
            case State(awaiting, epoch, signal) =>
              if (awaiting < capacity - 1) {
                val cleanup = state.update { s =>
                  if (epoch == s.epoch)
                    //The cyclic barrier hasn't been reset since the cancelled fiber start to await
                    s.copy(awaiting = s.awaiting - 1)
                  else s
                }

                val nextState = State(awaiting + 1, epoch, signal)
                (nextState, poll(signal.get).onCancel(cleanup))
              } else (State(0, epoch + 1, newSignal), signal.complete(()).void)
          }.flatten
        }
      }

    val remaining: F[Int] = state.get.map(s => capacity - s.awaiting)

    val awaiting: F[Int] = state.get.map(_.awaiting)

  }

  private[std] case class State[F[_]](
      awaiting: Int,
      epoch: Long,
      signal: Deferred[F, Unit])

  private[std] object State {
    def initial[F[_]](implicit F: GenConcurrent[F, _]): F[State[F]] =
      F.deferred[Unit].map { signal => State(0, 0, signal) }
  }
}
