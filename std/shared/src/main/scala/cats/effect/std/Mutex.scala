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

import scala.annotation.tailrec

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

    private[this] val acquire: F[Unit] = F.uncancelable { poll =>
      F.onCancel(
        poll(F.asyncCheckAttempt[Boolean] { cb =>
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
        }).flatMap { acquired =>
          if (acquired) F.unit // home free
          else poll(acquire) // wokened, but need to acquire
        },
        // If we were cancelled, that could mean
        // a lost wakeup from `release`, so we
        // wake up someone else instead of us;
        // the worst that could happen is an
        // unnecessary wakeup, which causes a
        // waiter to go to the end of the queue:
        F.delay(notifyOne())
      )
    }

    private[this] val _release: F[Unit] = F.delay {
      locked.set(false) // release
      notifyOne() // try to wake someone
    }

    private[this] val release: Unit => F[Unit] = _ => _release

    override final val lock: Resource[F, Unit] =
      Resource.makeFull[F, Unit](poll => poll(acquire))(release)

    override def mapK[G[_]](f: F ~> G)(implicit G: MonadCancel[G, _]): Mutex[G] =
      new Mutex.TransformedMutex(this, f)

    @tailrec
    private[this] final def notifyOne(): Unit = {
      val retry =
        try {
          val waiter = waiters.take()
          if (waiter ne null) {
            // wake the waiter; we don't
            // pass `true`, so it has to
            // go through the normal acquire,
            // so its cancellation is handled
            // properly:
            waiter(RightFalse)
            false
          } else {
            true
          }
        } catch {
          case FailureSignal =>
            false
        }

      if (retry) {
        notifyOne()
      }
    }
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
    override final val lock: Resource[G, Unit] =
      underlying.lock.mapK(f)

    override def mapK[H[_]](f: G ~> H)(implicit H: MonadCancel[H, _]): Mutex[H] =
      new Mutex.TransformedMutex(this, f)
  }

}
