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

import cats.effect.kernel.{Deferred, GenConcurrent}
import cats.effect.kernel.syntax.all._
import cats.syntax.all._
import cats.~>

/**
 * A synchronization abstraction that allows a set of fibers to wait until they all reach a
 * certain point.
 *
 * A cyclic barrier is initialized with a positive integer capacity n and a fiber waits by
 * calling [[await]], at which point it is semantically blocked until a total of n fibers are
 * blocked on the same cyclic barrier.
 *
 * At this point all the fibers are unblocked and the cyclic barrier is reset, allowing it to be
 * used again.
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
   * @return
   *   a cyclic barrier in the new context obtained by mapping the current one using the natural
   *   transformation `f`
   */
  def mapK[G[_]](f: F ~> G): CyclicBarrier[G] =
    new CyclicBarrier[G] {
      def await: G[Unit] = f(self.await)
    }

}

object CyclicBarrier {
  def apply[F[_]](capacity: Int)(implicit F: GenConcurrent[F, _]): F[CyclicBarrier[F]] = {
    if (capacity < 1)
      throw new IllegalArgumentException(
        s"Cyclic barrier constructed with capacity $capacity. Must be > 0")

    case class State(awaiting: Int, epoch: Long, unblock: Deferred[F, Unit])

    F.deferred[Unit].map(State(capacity, 0, _)).flatMap(F.ref).map { state =>
      new CyclicBarrier[F] {
        val await: F[Unit] =
          F.deferred[Unit].flatMap { gate =>
            state.flatModifyFull {
              case (poll, State(awaiting, epoch, unblock)) =>
                val awaitingNow = awaiting - 1

                if (awaitingNow == 0)
                  State(capacity, epoch + 1, gate) -> unblock.complete(()).void
                else {
                  val newState = State(awaitingNow, epoch, unblock)
                  // reincrement count if this await gets canceled,
                  // but only if the barrier hasn't reset in the meantime
                  val cleanup = state.update { s =>
                    if (s.epoch == epoch) s.copy(awaiting = s.awaiting + 1)
                    else s
                  }

                  newState -> poll(unblock.get).onCancel(cleanup)
                }

            }
          }
      }
    }
  }
}
