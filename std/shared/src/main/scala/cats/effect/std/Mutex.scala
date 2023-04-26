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

import java.util.concurrent.atomic.AtomicReference

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
    F match {
      case ff: Async[F] =>
        async[F](ff)

      case _ =>
        concurrent[F](F)
    }

  private[effect] def async[F[_]](implicit F: Async[F]): F[Mutex[F]] =
    in[F, F](F, F)

  private[effect] def concurrent[F[_]](implicit F: Concurrent[F]): F[Mutex[F]] =
    Semaphore[F](n = 1).map(sem => new ConcurrentImpl[F](sem))

  /**
   * Creates a new `Mutex`. Like `apply` but initializes state using another effect constructor.
   */
  def in[F[_], G[_]](implicit F: Sync[F], G: Async[G]): F[Mutex[G]] =
    F.delay {
      // Initialize the state with an already completed cell.
      val initialCell =
        AsyncImpl
          .LockQueueCell
          .create[G](
            new AtomicReference(
              AsyncImpl.LockQueueCell.completed
            ))
      new AtomicReference(initialCell)
    }.map { state => new AsyncImpl[G](state) }

  private final class ConcurrentImpl[F[_]](sem: Semaphore[F]) extends Mutex[F] {
    override final val lock: Resource[F, Unit] =
      sem.permit

    override def mapK[G[_]](f: F ~> G)(implicit G: MonadCancel[G, _]): Mutex[G] =
      new ConcurrentImpl(sem.mapK(f))
  }

  private final class AsyncImpl[F[_]](
      state: AtomicReference[AsyncImpl.LockQueueCell[F]]
  )(
      implicit F: Async[F]
  ) extends Mutex[F] {
    // Acquires the Mutex.
    private def acquire(poll: Poll[F]): F[AsyncImpl.LockQueueCell[F]] =
      AsyncImpl.LockQueueCell[F].flatMap { thisCell =>
        // Atomically get the last cell in the queue,
        // and put ourselves as the last one.
        F.delay(state.getAndSet(thisCell)).flatMap { previousCell =>
          // Then we will wait for the previous cell to complete.
          // There are two options:
          //  + None: Signaling that cell was holding the mutex and just finished,
          //    meaning we are free to acquire the mutex.
          //  + Some(next): Which means it was cancelled before it could acquire the mutex.
          //    We then receive the next cell in the queue, and we wait for that one (loop).
          //
          // Only the waiting process is cancelable.
          def loop(cell: AsyncImpl.LockQueueCell[F]): F[Unit] =
            F.onCancel(poll(cell.get), thisCell.cancel(next = cell)).flatMap {
              case None => F.unit
              case Some(next) => loop(cell = next)
            }

          loop(cell = previousCell).as(thisCell)
        }
      }

    // Releases the Mutex.
    private def release(thisCell: AsyncImpl.LockQueueCell[F]): F[Unit] =
      thisCell.complete

    override final val lock: Resource[F, Unit] =
      Resource.makeFull[F, AsyncImpl.LockQueueCell[F]](acquire)(release).void

    override def mapK[G[_]](f: F ~> G)(implicit G: MonadCancel[G, _]): Mutex[G] =
      new Mutex.TransformedMutex(this, f)
  }

  object AsyncImpl {
    // Represents a cell in the queue of waiters for the mutex lock.
    private[Mutex] sealed abstract class LockQueueCell[F[_]] {
      // Waits for this cell to complete or be canceled.
      def get: F[Option[LockQueueCell[F]]]

      // Completes this cell; i.e. frees the mutex lock.
      def complete: F[Unit]

      // Cancels this cell, removing it from the queue.
      def cancel(next: LockQueueCell[F]): F[Unit]
    }

    object LockQueueCell {
      private[Mutex] type CB[F[_]] = Either[Throwable, Option[LockQueueCell[F]]] => Unit
      private[Mutex] final val completed = (_: Any) => ()
      private[Mutex] final val RightNone = Right(None)

      def apply[F[_]](implicit F: Async[F]): F[LockQueueCell[F]] =
        F.delay(new AtomicReference[CB[F]]()).map { ref => create(ref) }

      def create[F[_]](
          ref: AtomicReference[CB[F]]
      )(
          implicit F: Async[F]
      ): LockQueueCell[F] =
        new LockQueueCell[F] {
          // A cell can be in three states.
          // + null: Nobody is waiting on us.
          // + cb: Someone is waiting for us.
          // + completed: We are not longer part of the queue (complete / cancel).

          override final val get: F[Option[LockQueueCell[F]]] =
            F.asyncCheckAttempt[Option[LockQueueCell[F]]] { cb =>
              F.delay {
                // Someone is going to wait for us.
                // First we check that we are still on the null state.
                if (ref.compareAndSet(null, cb)) {
                  // If so, we create the callback that will awake our waiter.
                  // In case they cancel their wait, we reset our sate to null.
                  // Race condition with our own cancel or complete,
                  // in that case they have priority.
                  Left(Some(F.delay(ref.compareAndSet(cb, null))))
                } else {
                  // If we are not in null, then we can only be on completed.
                  // Which means the mutex is free.
                  RightNone
                }
              }
            }

          override def complete: F[Unit] =
            F.delay {
              // Put us on the completed state.
              // And, if someone is waiting for us,
              // we notify them that the mutex is free.
              val cb = ref.getAndSet(completed)
              if (cb ne null) cb.apply(RightNone)
            }

          override def cancel(next: LockQueueCell[F]): F[Unit] =
            F.delay {
              // Put us on the completed state.
              // And, if someone is waiting for us,
              // we notify them that the should wait for the next cell in the queue.
              val cb = ref.getAndSet(completed)
              if (cb ne null) cb.apply(Right(Some(next)))
            }
        }
    }
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
