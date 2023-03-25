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

import java.util.concurrent.atomic.AtomicBoolean

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
   * Acquires the lock.
   *
   * @note
   *   In general prefer to use [[lock]] which returns a `Resource` that ensures the lock is
   *   properly released.
   * @see
   *   [[lock]] [[release]]
   */
  def acquire: F[Unit]

  /**
   * Releases the lock.
   *
   * @note
   *   In general prefer to use [[lock]] which returns a `Resource` that ensures the lock is
   *   properly released.
   * @see
   *   [[lock]] [[acquire]]
   */
  def release: F[Unit]

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
    F.delay(new AsyncImpl[G])

  private final class ConcurrentImpl[F[_]](sem: Semaphore[F]) extends Mutex[F] {
    override final val acquire: F[Unit] =
      sem.acquire

    override final val release: F[Unit] =
      sem.release

    override final val lock: Resource[F, Unit] =
      sem.permit

    override def mapK[G[_]](f: F ~> G)(implicit G: MonadCancel[G, _]): Mutex[G] =
      new ConcurrentImpl(sem.mapK(f))
  }

  private final class AsyncImpl[F[_]](implicit F: Async[F]) extends Mutex[F] {
    import AsyncImpl._

    private[this] val locked = new AtomicBoolean(false)
    private[this] val waiters = new UnsafeUnbounded[Either[Throwable, Boolean] => Unit]
    private[this] val FailureSignal = cats.effect.std.FailureSignal // prefetch

    override final val acquire: F[Unit] =
      F.asyncCheckAttempt[Boolean] { cb =>
        F.delay {
          if (locked.compareAndSet(false, true)) { // acquired
            RightTrue
          } else {
            val cancel = waiters.put(cb)
            if (locked.compareAndSet(false, true)) { // try again
              cancel()
              RightTrue
            } else {
              Left(Some(F.delay(cancel())))
            }
          }
        }
      }.flatMap { acquired =>
        if (acquired) F.unit // home free
        else acquire // wokened, but need to acquire
      }

    override final val release: F[Unit] = F.delay {
      try { // look for a waiter
        var waiter = waiters.take()
        while (waiter eq null) waiter = waiters.take()
        waiter(RightTrue) // pass the buck
      } catch { // no waiter found
        case FailureSignal =>
          locked.set(false) // release
          try {
            var waiter = waiters.take()
            while (waiter eq null) waiter = waiters.take()
            waiter(RightFalse) // waken any new waiters
          } catch {
            case FailureSignal => // do nothing
          }
      }
    }

    override final val lock: Resource[F, Unit] =
      Resource.makeFull[F, Unit](poll => poll(acquire))(_ => release)

    override def mapK[G[_]](f: F ~> G)(implicit G: MonadCancel[G, _]): Mutex[G] =
      new Mutex.TransformedMutex(this, f)
  }

  private object AsyncImpl {
    private val RightTrue = Right(true)
    private val RightFalse = Right(false)
  }

  private final class TransformedMutex[F[_], G[_]](
      underlying: Mutex[F],
      f: F ~> G
  )(implicit F: MonadCancel[F, _], G: MonadCancel[G, _])
      extends Mutex[G] {
    override final val acquire: G[Unit] =
      f(underlying.acquire)

    override final val release: G[Unit] =
      f(underlying.release)

    override final val lock: Resource[G, Unit] =
      underlying.lock.mapK(f)

    override def mapK[H[_]](f: G ~> H)(implicit H: MonadCancel[H, _]): Mutex[H] =
      new Mutex.TransformedMutex(this, f)
  }

}
