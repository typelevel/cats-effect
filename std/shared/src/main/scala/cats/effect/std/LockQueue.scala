/*
 * Copyright 2020-2025 Typelevel
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
package std

import cats.effect.kernel._
import cats.syntax.all._

// Represents a queue of waiters for the lock.
private[effect] object LockQueue {
  // Phantom type for the cells in the queue.
  final type Cell = AnyRef

  // Represents the first cell of the queue.
  final val EmptyCell: Cell = null

  // Represents a waiting cell in the queue.
  final type WaitingCell[F[_]] = Deferred[F, Cell]

  // Creates a new waiting cell.
  def WaitingCell[F[_]](implicit F: Concurrent[F]): F[WaitingCell[F]] =
    Deferred[F, Cell]

  // This is a variant of the Craig, Landin, and Hagersten (CLH) queue lock.
  // Queue nodes (called cells below) are `Deferred`s,
  // so fibers can suspend and wake up (instead of spinning, like in the original algorithm).
  def lock[F[_]](
      queue: Ref[F, Cell]
  )(
      implicit F: Concurrent[F]
  ): Resource[F, Unit] = {
    // Awakes whoever is waiting for our waiting cell,
    // and give them the next cell in the queue.
    def awakeCell(
        ourCell: WaitingCell[F],
        nextCell: Cell
    ): F[Unit] =
      queue.access.flatMap {
        // If the current last cell in the queue is our cell,
        // then that means nobody is waiting for us.
        // Thus, we can just set the state to the next cell in the queue.
        // Otherwise, we awake whoever is waiting for us.
        case (lastCell, setter) =>
          if (lastCell eq ourCell) setter(nextCell)
          else F.pure(false)
      } flatMap {
        case false => ourCell.complete(nextCell).void
        case true => F.unit
      }

    // Cancels a Fiber waiting for the Lock.
    def cancel(
        ourCell: WaitingCell[F],
        nextCell: Cell
    ): F[Unit] =
      awakeCell(ourCell, nextCell)

    // Acquires the Lock.
    def acquire(poll: Poll[F]): F[WaitingCell[F]] =
      WaitingCell[F].flatMap { ourCell =>
        // Atomically get the last cell in the queue,
        // and put ourselves as the last one.
        queue.getAndSet(ourCell).flatMap { lastCell =>
          // Then we check what the next cell is.
          // There are two options:
          //  + EmptyCell: Signaling that the lock is free.
          //  + WaitingCell: Which means there is someone ahead of us in the queue.
          //    Thus, we wait for that cell to complete; and then check again.
          //
          // Only the waiting process is cancelable.
          // If we are cancelled while waiting,
          // we notify our waiter with the cell ahead of us.
          def loop(
              nextCell: Cell
          ): F[WaitingCell[F]] =
            if (nextCell eq EmptyCell) F.pure(ourCell)
            else {
              F.onCancel(
                poll(nextCell.asInstanceOf[WaitingCell[F]].get),
                cancel(ourCell, nextCell)
              ).flatMap(loop)
            }

          loop(nextCell = lastCell)
        }
      }

    // Releases the Lock.
    def release(ourCell: WaitingCell[F]): F[Unit] =
      awakeCell(ourCell, nextCell = EmptyCell)

    // The Lock is a Resource over a new waiting cell at the end of the queue.
    Resource.makeFull[F, WaitingCell[F]](acquire)(release).void
  }
}
