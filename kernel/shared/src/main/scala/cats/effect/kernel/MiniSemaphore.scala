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

package cats
package effect
package kernel

import cats.syntax.all._

import scala.collection.immutable.{Queue => ScalaQueue}

/**
 * A cut-down version of semaphore used to implement
 * parTraverseN
 */
private[kernel] abstract class MiniSemaphore[F[_]] {

  /**
   * Acquires a single permit.
   */
  def acquire: F[Unit]

  /**
   * Releases a single permit.
   */
  def release: F[Unit]

  /**
   * Sequence an action while holding a permit
   */
  def withPermit[A](fa: F[A]): F[A]

}

private[kernel] object MiniSemaphore {

  /**
   * Creates a new `Semaphore`, initialized with `n` available permits.
   */
  def apply[F[_]](n: Int)(implicit F: GenConcurrent[F, _]): F[MiniSemaphore[F]] = {
    requireNonNegative(n)
    F.ref[State[F]](Right(n)).map(stateRef => new ConcurrentMiniSemaphore[F](stateRef))
  }

  private def requireNonNegative(n: Int): Unit =
    require(n >= 0, s"n must be nonnegative, was: $n")

  // A semaphore is either empty, and there are number of outstanding acquires (Left)
  // or it is non-empty, and there are n permits available (Right)
  private type State[F[_]] = Either[ScalaQueue[Deferred[F, Unit]], Int]

  private final case class Permit[F[_]](await: F[Unit], release: F[Unit])

  private class ConcurrentMiniSemaphore[F[_]](state: Ref[F, State[F]])(
      implicit F: GenConcurrent[F, _])
      extends MiniSemaphore[F] {

    def acquire: F[Unit] =
      F.bracketCase(acquireInternal)(_.await) {
        case (promise, Outcome.Canceled()) => promise.release
        case _ => F.unit
      }

    private def acquireInternal: F[Permit[F]] = {
      F.deferred[Unit].flatMap { gate =>
        state
          .updateAndGet {
            case Left(waiting) =>
              Left(waiting :+ gate)
            case Right(m) =>
              if (m > 0)
                Right(m - 1)
              else
                Left(ScalaQueue(gate))
          }
          .map {
            case Left(_) =>
              val cleanup = state.update {
                case Left(waiting) => Left(waiting.filterNot(_ eq gate))
                case Right(m) => Right(m)
              }

              Permit(gate.get, cleanup)

            case Right(_) => Permit(F.unit, release)
          }
      }
    }

    def release: F[Unit] =
      F.uncancelable { _ =>
        state.modify { st =>
          st match {
            case Left(waiting) =>
              if (waiting.isEmpty)
                (Right(1), F.unit)
              else
                (Left(waiting.tail), waiting.head.complete(()).void)
            case Right(m) =>
              (Right(m + 1), F.unit)
          }
        }.flatten
      }

    def withPermit[A](fa: F[A]): F[A] =
      F.bracket(acquire)(_ => fa)(_ => release)
  }

}
