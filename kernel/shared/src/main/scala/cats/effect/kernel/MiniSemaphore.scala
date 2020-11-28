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
import cats.effect.kernel.syntax.all._
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
   * `n` must be > 0
   */
  def apply[F[_]](n: Int)(implicit F: GenConcurrent[F, _]): F[MiniSemaphore[F]] = {
     require(n >= 0, s"n must be nonnegative, was: $n")
    // this representation appears more intuitive at first, but it's
    // actually weird, since a semaphore at zero permits is Right(0)
    // in `acquire`, but Left(empty) in `release`
    type State = Either[ScalaQueue[Deferred[F, Unit]], Int]

    F.ref[State](Right(n)).map { state =>
      new MiniSemaphore[F] {
        def acquire: F[Unit] =
          F.uncancelable { poll =>
            F.deferred[Unit].flatMap { wait =>
              val cleanup = state.update {
                case Left(waiting) => Left(waiting.filterNot(_ eq wait))
                case Right(m) => Right(m)
              }

              state.modify {
                case Right(permits) =>
                  if (permits == 0)
                    Left(ScalaQueue(wait)) -> poll(wait.get).onCancel(cleanup)
                  else
                    Right(permits - 1) -> ().pure[F]

                case Left(waiting) =>
                  Left(waiting :+ wait) -> poll(wait.get).onCancel(cleanup)
              }.flatten
            }
          }

        def release: F[Unit] =
          state.modify { st =>
            st match {
              case Left(waiting) =>
                if (waiting.isEmpty)
                  Right(1) -> ().pure[F]
                else
                  Left(waiting.tail) -> waiting.head.complete(()).void
              case Right(m) =>
                Right(m + 1) -> ().pure[F]
            }
          }.flatten
            .uncancelable

        def withPermit[A](fa: F[A]): F[A] =
          F.uncancelable { poll =>
            poll(acquire) >> poll(fa).guarantee(release)
          }
      }
    }
  }
}
