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
package std

import cats.effect.kernel._
import cats.syntax.all._

/**
 * A purely functional keyed mutex.
 *
 * A mutex is a concurrency primitive that can be used to give access to a resource to only one
 * fiber at a time; e.g. a [[cats.effect.kernel.Ref]].
 *
 * '''Note''': This lock is not reentrant, thus this
 * `mutex.lock(key).surround(mutex.lock(key).use_)` will deadlock.
 *
 * @see
 *   [[cats.effect.std.Mutex]]
 */
abstract class KeyedMutex[F[_], K] {

  /**
   * Returns a [[cats.effect.kernel.Resource]] that acquires the lock for the given `key`, holds
   * it for the lifetime of the resource, then releases it.
   */
  def lock(key: K): Resource[F, Unit]

  /**
   * Modify the context `F` using natural transformation `f`.
   */
  def mapK[G[_]](f: F ~> G)(implicit G: MonadCancel[G, _]): KeyedMutex[G, K]
}

object KeyedMutex {

  /**
   * Creates a new `KeyedMutex`.
   */
  def apply[F[_], K](implicit F: Concurrent[F]): F[KeyedMutex[F, K]] =
    Ref
      .of[F, ConcurrentImpl.LockQueueCell](
        // Initialize the state with an already completed cell.
        ConcurrentImpl.EmptyCell
      )
      .map(state => new ConcurrentImpl[F, K](state))

  /**
   * Creates a new `KeyedMutex`. Like `apply` but initializes state using another effect
   * constructor.
   */
  def in[F[_], G[_], K](implicit F: Sync[F], G: Async[G]): F[KeyedMutex[G, K]] =
    Ref
      .in[F, G, ConcurrentImpl.LockQueueCell](
        // Initialize the state with an already completed cell.
        ConcurrentImpl.EmptyCell
      )
      .map(state => new ConcurrentImpl[G, K](state))

  private final class ConcurrentImpl[F[_], K](
      state: Ref[F, ConcurrentImpl.LockQueueCell]
  )(
      implicit F: Concurrent[F]
  ) extends KeyedMutex[F, K] {

    // This is a variant of the Craig, Landin, and Hagersten
    // (CLH) queue lock. Queue nodes (called cells below)
    // are `Deferred`s, so fibers can suspend and wake up
    // (instead of spinning, like in the original algorithm).

    // Awakes whoever is waiting for us with the next cell in the queue.
    private def awakeCell(
        ourCell: ConcurrentImpl.WaitingCell[F],
        nextCell: ConcurrentImpl.LockQueueCell
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
        ourCell: ConcurrentImpl.WaitingCell[F],
        nextCell: ConcurrentImpl.LockQueueCell
    ): F[Unit] =
      awakeCell(ourCell, nextCell)

    // Acquires the Mutex.
    private def acquire(poll: Poll[F]): F[ConcurrentImpl.WaitingCell[F]] =
      ConcurrentImpl.LockQueueCell[F].flatMap { ourCell =>
        // Atomically get the last cell in the queue,
        // and put ourselves as the last one.
        state.getAndSet(ourCell).flatMap { lastCell =>
          // Then we check what the next cell is.
          // There are two options:
          //  + EmptyCell: Signaling that the mutex is free.
          //  + WaitingCell: Which means there is someone ahead of us in the queue.
          //    Thus, we wait for that cell to complete; and then check again.
          //
          // Only the waiting process is cancelable.
          // If we are cancelled while waiting,
          // we notify our waiter with the cell ahead of us.
          def loop(
              nextCell: ConcurrentImpl.LockQueueCell
          ): F[ConcurrentImpl.WaitingCell[F]] =
            if (nextCell eq ConcurrentImpl.EmptyCell) F.pure(ourCell)
            else {
              F.onCancel(
                poll(nextCell.asInstanceOf[ConcurrentImpl.WaitingCell[F]].get),
                cancel(ourCell, nextCell)
              ).flatMap(loop)
            }

          loop(nextCell = lastCell)
        }
      }

    // Releases the Mutex.
    private def release(ourCell: ConcurrentImpl.WaitingCell[F]): F[Unit] =
      awakeCell(ourCell, nextCell = ConcurrentImpl.EmptyCell)

    override def lock(key: K): Resource[F, Unit] =
      Resource.makeFull[F, ConcurrentImpl.WaitingCell[F]](acquire)(release).void

    override def mapK[G[_]](f: F ~> G)(implicit G: MonadCancel[G, _]): KeyedMutex[G, K] =
      new KeyedMutex.TransformedKeyedMutex(this, f)
  }

  private object ConcurrentImpl {
    // Represents a queue of waiters for the mutex.
    private[KeyedMutex] final type LockQueueCell = AnyRef
    // Represents the first cell of the queue.
    private[KeyedMutex] final type EmptyCell = LockQueueCell
    private[KeyedMutex] final val EmptyCell: EmptyCell = null
    // Represents a waiting cell in the queue.
    private[KeyedMutex] final type WaitingCell[F[_]] = Deferred[F, LockQueueCell]

    private[KeyedMutex] def LockQueueCell[F[_]](implicit F: Concurrent[F]): F[WaitingCell[F]] =
      Deferred[F, LockQueueCell]
  }

  private final class TransformedKeyedMutex[F[_], G[_], K](
      underlying: KeyedMutex[F, K],
      f: F ~> G
  )(implicit F: MonadCancel[F, _], G: MonadCancel[G, _])
      extends KeyedMutex[G, K] {
    override def lock(key: K): Resource[G, Unit] =
      underlying.lock(key).mapK(f)

    override def mapK[H[_]](f: G ~> H)(implicit H: MonadCancel[H, _]): KeyedMutex[H, K] =
      new KeyedMutex.TransformedKeyedMutex(this, f)
  }
}
