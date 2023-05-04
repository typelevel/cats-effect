/*
 * Copyright 2020-2023 Typelevel
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

/**
 * A purely functional mutex.
 *
 * A mutex is a concurrency primitive that can be used to give access to a resource to only one
 * fiber at a time; e.g. a [[cats.effect.kernel.Ref]].
 *
 * {{{
 * // Assuming some resource r that should not be used concurrently.
 *
 * Mutex[IO].flatMap { mutex =>
 *   mutex.lock.surround {
 *     // Here you can use r safely.
 *     IO(r.mutate(...))
 *   }
 * }
 * }}}
 *
 * '''Note''': This lock is not reentrant, thus this `mutex.lock.surround(mutex.lock.use_)` will
 * deadlock.
 *
 * @see
 *   [[cats.effect.std.AtomicCell]]
 */
abstract class Mutex[F[_]] {

  /**
   * Returns a [[cats.effect.kernel.Resource]] that acquires the lock, holds it for the lifetime
   * of the resource, then releases it.
   */
  def lock: Resource[F, Unit]

  /**
   * Modify the context `F` using natural transformation `f`.
   */
  def mapK[G[_]](f: F ~> G)(implicit G: MonadCancel[G, _]): Mutex[G]
}

object Mutex {

  /**
   * Creates a new `Mutex`.
   */
  def apply[F[_]](implicit F: Concurrent[F]): F[Mutex[F]] =
    Ref
      .of[F, ConcurrentImpl.LockQueue](
        // Initialize the state with an already completed cell.
        ConcurrentImpl.Empty
      )
      .map(state => new ConcurrentImpl[F](state))

  /**
   * Creates a new `Mutex`. Like `apply` but initializes state using another effect constructor.
   */
  def in[F[_], G[_]](implicit F: Sync[F], G: Async[G]): F[Mutex[G]] =
    Ref
      .in[F, G, ConcurrentImpl.LockQueue](
        // Initialize the state with an already completed cell.
        ConcurrentImpl.Empty
      )
      .map(state => new ConcurrentImpl[G](state))

  private final class ConcurrentImpl[F[_]](
      state: Ref[F, ConcurrentImpl.LockQueue]
  )(
      implicit F: Concurrent[F]
  ) extends Mutex[F] {
    // Awakes whoever is waiting for us with the next cell in the queue.
    private def awakeCell(
        ourCell: ConcurrentImpl.Next[F],
        nextCell: ConcurrentImpl.LockQueue
    ): F[Unit] =
      state.access.flatMap {
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

    // Cancels a Fiber waiting for the Mutex.
    private def cancel(
        ourCell: ConcurrentImpl.Next[F],
        currentCell: ConcurrentImpl.LockQueue
    ): F[Unit] =
      awakeCell(ourCell, nextCell = currentCell)

    // Acquires the Mutex.
    private def acquire(poll: Poll[F]): F[ConcurrentImpl.Next[F]] =
      ConcurrentImpl.LockQueueCell[F].flatMap { ourCell =>
        // Atomically get the last cell in the queue,
        // and put ourselves as the last one.
        state.getAndSet(ourCell).flatMap { lastCell =>
          // Then we check what was the current cell is.
          // There are two options:
          //  + Empty: Signaling that the mutex is free.
          //  + Next(cell): Which means there is someone ahead of us in the queue.
          //    Thus, wait for that cell to complete; and check again.
          //
          // Only the waiting process is cancelable.
          // If we are cancelled while waiting,
          // we then notify our waiter to wait for the cell ahead of us instead.
          def loop(currentCell: ConcurrentImpl.LockQueue): F[ConcurrentImpl.Next[F]] =
            if (currentCell eq ConcurrentImpl.Empty) F.pure(ourCell)
            else {
              F.onCancel(
                poll(currentCell.asInstanceOf[ConcurrentImpl.Next[F]].get),
                cancel(ourCell, currentCell)
              ).flatMap { nextCell => loop(currentCell = nextCell) }
            }

          loop(currentCell = lastCell)
        }
      }

    // Releases the Mutex.
    private def release(ourCell: ConcurrentImpl.Next[F]): F[Unit] =
      awakeCell(ourCell, nextCell = ConcurrentImpl.Empty)

    override final val lock: Resource[F, Unit] =
      Resource.makeFull[F, ConcurrentImpl.Next[F]](acquire)(release).void

    override def mapK[G[_]](f: F ~> G)(implicit G: MonadCancel[G, _]): Mutex[G] =
      new Mutex.TransformedMutex(this, f)
  }

  private object ConcurrentImpl {
    // Represents a queue of waiters for the mutex.
    private[Mutex] final type LockQueue = AnyRef
    // Represents the first cell of the queue.
    private[Mutex] final type Empty = LockQueue
    private[Mutex] final val Empty: Empty = null
    // Represents a cell in the queue of waiters.
    private[Mutex] final type Next[F[_]] = Deferred[F, LockQueue]

    private[Mutex] def LockQueueCell[F[_]](implicit F: Concurrent[F]): F[Next[F]] =
      Deferred[F, LockQueue]
  }

  private final class TransformedMutex[F[_], G[_]](
      underlying: Mutex[F],
      f: F ~> G
  )(implicit F: MonadCancel[F, _], G: MonadCancel[G, _])
      extends Mutex[G] {
    override final val lock: Resource[G, Unit] =
      underlying.lock.mapK(f)

    override def mapK[H[_]](f: G ~> H)(implicit H: MonadCancel[H, _]): Mutex[H] =
      new Mutex.TransformedMutex(this, f)
  }
}
