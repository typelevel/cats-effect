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
      val initialCell =
        AsyncImpl
          .LockChainCell
          .create[G](
            new AtomicReference(
              AsyncImpl.LockChainCell.Sentinel
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
      state: AtomicReference[AsyncImpl.LockChainCell[F]]
  )(
      implicit F: Async[F]
  ) extends Mutex[F] {
    // Acquires the Mutex.
    private def acquire(poll: Poll[F]): F[AsyncImpl.LockChainCell[F]] =
      AsyncImpl.LockChainCell[F].flatMap { thisCell =>
        F.delay(state.getAndSet(thisCell)).flatMap { previousCell =>
          def loop(cell: AsyncImpl.LockChainCell[F]): F[Unit] =
            F.onCancel(poll(cell.next), thisCell.resume(next = cell)).flatMap {
              case None => F.unit
              case Some(c) => loop(c)
            }

          loop(previousCell).as(thisCell)
        }
      }

    // Releases the Mutex.
    private def release(thisCell: AsyncImpl.LockChainCell[F]): F[Unit] =
      thisCell.finish

    override final val lock: Resource[F, Unit] =
      Resource.makeFull[F, AsyncImpl.LockChainCell[F]](acquire)(release).void

    override def mapK[G[_]](f: F ~> G)(implicit G: MonadCancel[G, _]): Mutex[G] =
      new Mutex.TransformedMutex(this, f)
  }

  object AsyncImpl {
    private[Mutex] sealed abstract class LockChainCell[F[_]] {
      def next: F[Option[LockChainCell[F]]]
      def resume(next: LockChainCell[F]): F[Unit]
      def finish: F[Unit]
    }

    object LockChainCell {
      private[Mutex] type CB[F[_]] = Either[Throwable, Option[LockChainCell[F]]] => Unit
      private[Mutex] final val Sentinel = (_: Any) => ()
      private[Mutex] final val RightNone = Right(None)

      def apply[F[_]](implicit F: Async[F]): F[LockChainCell[F]] =
        F.delay(new AtomicReference[CB[F]]()).map { ref => create(ref) }

      def create[F[_]](
          ref: AtomicReference[CB[F]]
      )(
          implicit F: Async[F]
      ): LockChainCell[F] =
        new LockChainCell[F] {
          override final val next: F[Option[LockChainCell[F]]] =
            F.asyncCheckAttempt[Option[LockChainCell[F]]] { thisCB =>
              F.delay {
                if (ref.compareAndSet(null, thisCB)) {
                  Left(Some(F.delay(ref.compareAndSet(thisCB, null))))
                } else {
                  RightNone
                }
              }
            }

          override def resume(next: LockChainCell[F]): F[Unit] =
            F.delay {
              val cb = ref.getAndSet(Sentinel)
              if (cb ne null) cb.apply(Right(Some(next)))
            }

          override def finish: F[Unit] =
            F.delay {
              val cb = ref.getAndSet(Sentinel)
              if (cb ne null) cb.apply(RightNone)
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
