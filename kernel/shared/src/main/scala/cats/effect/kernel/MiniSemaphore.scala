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

package cats
package effect
package kernel

import cats.effect.kernel.syntax.all._
import cats.syntax.all._

import scala.collection.immutable.{Queue => ScalaQueue}

/**
 * A cut-down version of semaphore used to implement parTraverseN
 */
private[kernel] abstract class MiniSemaphore[F[_]] extends Serializable {

  /**
   * Sequence an action while holding a permit
   */
  def withPermit[A](fa: F[A]): F[A]
}

private[kernel] object MiniSemaphore {

  /**
   * Creates a new `Semaphore`, initialized with `n` available permits. `n` must be > 0
   */
  def apply[F[_]](n: Int)(implicit F: GenConcurrent[F, _]): F[MiniSemaphore[F]] = {
    require(n >= 0, s"n must be nonnegative, was: $n")

    /*
     * Invariant:
     *    (waiting.empty && permits >= 0) || (permits.nonEmpty && permits == 0)
     *
     * The alternative representation:
     *
     *    Either[ScalaQueue[Deferred[F, Unit]], Int]
     *
     * is less intuitive, since a semaphore with no permits can either
     * be Left(empty) or Right(0)
     */
    case class State(
        waiting: ScalaQueue[Deferred[F, Unit]],
        permits: Int
    )

    F.ref(State(ScalaQueue(), n)).map { state =>
      new MiniSemaphore[F] {
        def acquire: F[Unit] =
          F.uncancelable { poll =>
            F.deferred[Unit].flatMap { wait =>
              val cleanup = state.update {
                case s @ State(waiting, permits) =>
                  if (waiting.nonEmpty)
                    State(waiting.filterNot(_ eq wait), permits)
                  else s
              }

              state.modify {
                case State(waiting, permits) =>
                  if (permits == 0)
                    State(waiting :+ wait, permits) -> poll(wait.get).onCancel(cleanup)
                  else
                    State(waiting, permits - 1) -> ().pure[F]
              }.flatten
            }
          }

        def release: F[Unit] =
          state.flatModify {
            case State(waiting, permits) =>
              if (waiting.nonEmpty)
                State(waiting.tail, permits) -> waiting.head.complete(()).void
              else
                State(waiting, permits + 1) -> ().pure[F]
          }

        def withPermit[A](fa: F[A]): F[A] =
          F.uncancelable { poll => poll(acquire) >> poll(fa).guarantee(release) }
      }
    }
  }
}
