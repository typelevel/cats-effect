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
 * A synchronized, concurrent, mutable reference.
 *
 * Provides safe concurrent access and modification of its contents, by ensuring only one fiber
 * can operate on them at the time. Thus, all operations except `get` may semantically block the
 * calling fiber.
 *
 * {{{
 *   final class ParkingLot(data: AtomicCell[IO, Vector[Boolean]], rnd: Random[IO]) {
 *     def getSpot: IO[Option[Int]] =
 *       data.evalModify { spots =>
 *         val availableSpots = spots.zipWithIndex.collect { case (true, idx) => idx }
 *         rnd.shuffleVector(availableSpots).map { shuffled =>
 *           val acquired = shuffled.headOption
 *           val next = acquired.fold(spots)(a => spots.updated(a, false)) // mark the chosen spot as taken
 *           (next, shuffled.headOption)
 *         }
 *       }
 *   }
 * }}}
 *
 * @see
 *   [[cats.effect.kernel.Ref]] for a non-blocking alternative.
 */
abstract class AtomicCell[F[_], A] {

  /**
   * Obtains the current value.
   */
  def get: F[A]

  /**
   * Sets the current value to `a`.
   */
  def set(a: A): F[Unit]

  /**
   * Like `update` but allows the update function to return an output value.
   */
  def modify[B](f: A => (A, B)): F[B]

  /**
   * Like `evalUpdate` but allows the update function to return an output value.
   */
  def evalModify[B](f: A => F[(A, B)]): F[B]

  /**
   * Modifies the current value using the supplied update function.
   */
  def update(f: A => A): F[Unit] =
    modify { a => (f(a), ()) }

  /**
   * Like `update` but using an effectual function; which is guaranteed to run only once.
   */
  def evalUpdate(f: A => F[A]): F[Unit]

  /**
   * Updates the current value using the provided function, and returns the previous value.
   */
  def getAndUpdate(f: A => A): F[A] =
    modify { a => (f(a), a) }

  /**
   * Updates the current value using the provided effectual function, and returns the previous
   * value.
   */
  def evalGetAndUpdate(f: A => F[A]): F[A]

  /**
   * Updates the current value using the provided function, and returns the updated value.
   */
  def updateAndGet(f: A => A): F[A] =
    modify { a =>
      val aa = f(a)
      (aa, aa)
    }

  /**
   * Updates the current value using the provided effectual function, and returns the updated
   * value.
   */
  def evalUpdateAndGet(f: A => F[A]): F[A]

  /**
   * Replaces the current value with `a`, returning the previous value.
   */
  def getAndSet(a: A): F[A] =
    getAndUpdate(_ => a)
}

object AtomicCell {

  /**
   * Builds a new `AtomicCell`
   *
   * {{{
   *   AtomicCell[IO].of(10)
   *   AtomicCell[IO].empty[Int]
   * }}}
   */
  def apply[F[_]]: ApplyPartiallyApplied[F] =
    new ApplyPartiallyApplied(dummy = true)

  final class ApplyPartiallyApplied[F[_]](private val dummy: Boolean) extends AnyVal {

    /**
     * Initializes the `AtomicCell` using the provided value.
     */
    def of[A](init: A)(implicit F: Concurrent[F]): F[AtomicCell[F, A]] =
      F match {
        case f: Async[F] =>
          AtomicCell.async(init)(f)
        case _ =>
          AtomicCell.concurrent(init)
      }

    @deprecated("Use the version that only requires Concurrent", since = "3.5.0")
    private[std] def of[A](init: A, F: Async[F]): F[AtomicCell[F, A]] =
      of(init)(F)

    /**
     * Initializes the `AtomicCell` using the default empty value of the provided type.
     */
    def empty[A](implicit M: Monoid[A], F: Concurrent[F]): F[AtomicCell[F, A]] =
      of(M.empty)

    @deprecated("Use the version that only requires Concurrent", since = "3.5.0")
    private[std] def empty[A](M: Monoid[A], F: Async[F]): F[AtomicCell[F, A]] =
      of(M.empty)(F)
  }

  private[effect] def async[F[_], A](init: A)(implicit F: Async[F]): F[AtomicCell[F, A]] =
    Mutex.apply[F].map(mutex => new AsyncImpl(init, mutex))

  private[effect] def concurrent[F[_], A](init: A)(
      implicit F: Concurrent[F]): F[AtomicCell[F, A]] =
    (Ref.of[F, A](init), Mutex.apply[F]).mapN { (ref, m) => new ConcurrentImpl(ref, m) }

  private final class ConcurrentImpl[F[_], A](
      ref: Ref[F, A],
      mutex: Mutex[F]
  )(
      implicit F: Concurrent[F]
  ) extends AtomicCell[F, A] {
    override def get: F[A] = ref.get

    override def set(a: A): F[Unit] =
      mutex.lock.surround(ref.set(a))

    override def modify[B](f: A => (A, B)): F[B] =
      evalModify(a => F.pure(f(a)))

    override def evalModify[B](f: A => F[(A, B)]): F[B] =
      mutex.lock.surround {
        ref.get.flatMap(f).flatMap {
          case (a, b) =>
            ref.set(a).as(b)
        }
      }

    override def evalUpdate(f: A => F[A]): F[Unit] =
      evalModify(a => f(a).map(aa => (aa, ())))

    override def evalGetAndUpdate(f: A => F[A]): F[A] =
      evalModify(a => f(a).map(aa => (aa, a)))

    override def evalUpdateAndGet(f: A => F[A]): F[A] =
      evalModify(a => f(a).map(aa => (aa, aa)))
  }

  private final class AsyncImpl[F[_], A](
      init: A,
      mutex: Mutex[F]
  )(
      implicit F: Async[F]
  ) extends AtomicCell[F, A] {
    @volatile private var cell: A = init

    override def get: F[A] =
      F.delay {
        cell
      }

    override def set(a: A): F[Unit] =
      mutex.lock.surround {
        F.delay {
          cell = a
        }
      }

    override def modify[B](f: A => (A, B)): F[B] =
      evalModify(a => F.pure(f(a)))

    override def evalModify[B](f: A => F[(A, B)]): F[B] =
      mutex.lock.surround {
        F.delay(cell).flatMap(f).flatMap {
          case (a, b) =>
            F.delay {
              cell = a
              b
            }
        }
      }

    override def evalUpdate(f: A => F[A]): F[Unit] =
      evalModify(a => f(a).map(aa => (aa, ())))

    override def evalGetAndUpdate(f: A => F[A]): F[A] =
      evalModify(a => f(a).map(aa => (aa, a)))

    override def evalUpdateAndGet(f: A => F[A]): F[A] =
      evalModify(a => f(a).map(aa => (aa, aa)))
  }
}
