/*
 * Copyright 2020-2022 Typelevel
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

  def async[F[_]](implicit F: Async[F]): F[Mutex[F]] =
    in[F, F](F, F)

  def concurrent[F[_]](implicit F: Concurrent[F]): F[Mutex[F]] =
    Semaphore[F](n = 1).map(sem => new ConcurrentImpl[F](sem))

  /**
   * Creates a new `Mutex`. Like `apply` but initializes state using another effect constructor.
   */
  def in[F[_], G[_]](implicit F: Sync[F], G: Async[G]): F[Mutex[G]] =
    F.delay(
      new AtomicReference[LockCell]()
    ).map(state => new AsyncImpl[G](state)(G))

  private final class ConcurrentImpl[F[_]](sem: Semaphore[F]) extends Mutex[F] {
    override final val lock: Resource[F, Unit] =
      sem.permit

    override def mapK[G[_]](f: F ~> G)(implicit G: MonadCancel[G, _]): Mutex[G] =
      new ConcurrentImpl(sem.mapK(f))
  }

  private final class AsyncImpl[F[_]](state: AtomicReference[LockCell])(implicit F: Async[F])
      extends Mutex[F] {
    // Cancels a Fiber waiting for the Mutex.
    private def cancel(thisCB: CB, thisCell: LockCell, previousCell: LockCell): F[Unit] =
      F.delay {
        // If we are canceled.
        // First, we check if the state still contains ourselves,
        // if that is the case, we swap it with the previousCell.
        // This ensures any consequent attempt to acquire the Mutex
        // will register its callback on the appropriate cell.
        // Additionally, that confirms there is no Fiber
        // currently waiting for us.
        if (!state.compareAndSet(thisCell, previousCell)) {
          // Otherwise,
          // it means we have a Fiber waiting for us.
          // Thus, we need to tell the previous cell
          // to awake that Fiber instead.
          var nextCB = thisCell.get()
          while (nextCB eq null) {
            // There is a tiny fraction of time when
            // the next cell has acquired ourselves,
            // but hasn't registered itself yet.
            // Thus, we spin loop until that happens
            nextCB = thisCell.get()
          }
          if (!previousCell.compareAndSet(thisCB, nextCB)) {
            // However, in case the previous cell had already completed,
            // then the Mutex is free and we can awake our waiting fiber.
            if (nextCB ne null) nextCB.apply(Either.unit)
          }
        }
      }

    // Awaits until the Mutex is free.
    private def await(thisCell: LockCell): F[Unit] =
      F.asyncCheckAttempt[Unit] { thisCB =>
        F.delay {
          val previousCell = state.getAndSet(thisCell)

          if (previousCell eq null) {
            // If the previous cell was null,
            // then the Mutex is free.
            Either.unit
          } else {
            // Otherwise,
            // we check again that the previous cell haven't been completed yet,
            // if not we tell the previous cell to awake us when they finish.
            if (!previousCell.compareAndSet(null, thisCB)) {
              // If it was already completed,
              // then the Mutex is free.
              Either.unit
            } else {
              Left(Some(cancel(thisCB, thisCell, previousCell)))
            }
          }
        }
      }

    // Acquires the Mutex.
    private def acquire(poll: Poll[F]): F[LockCell] =
      F.delay(new AtomicReference[CB]()).flatMap { thisCell =>
        poll(await(thisCell).map(_ => thisCell))
      }

    // Releases the Mutex.
    private def release(thisCell: LockCell): F[Unit] =
      F.delay {
        // If the state still contains our own cell,
        // then it means nobody was waiting for the Mutex,
        // and thus it can be put on a free state again.
        if (!state.compareAndSet(thisCell, null)) {
          // Otherwise,
          // our cell is probably not empty,
          // we must awake whatever Fiber is waiting for us.
          val nextCB = thisCell.getAndSet(Sentinel)
          if (nextCB ne null) nextCB.apply(Either.unit)
        }
      }

    override final val lock: Resource[F, Unit] =
      Resource.makeFull[F, LockCell](acquire)(release).void

    override def mapK[G[_]](f: F ~> G)(implicit G: MonadCancel[G, _]): Mutex[G] =
      new Mutex.TransformedMutex(this, f)
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

  private type CB = Either[Throwable, Unit] => Unit

  private final val Sentinel: CB = _ => ()

  private type LockCell = AtomicReference[CB]
}
