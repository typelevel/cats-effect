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

  private[effect] def async[F[_], A](
      init: A
  )(
      implicit F: Async[F]
  ): F[AtomicCell[F, A]] =
    Mutex.apply[F].map(mutex => new AsyncImpl(init, lock = mutex.lock))

  private[effect] def concurrent[F[_], A](
      init: A
  )(
      implicit F: Concurrent[F]
  ): F[AtomicCell[F, A]] =
    (Ref.of[F, A](init), Mutex.apply[F]).mapN { (ref, mutex) =>
      new ConcurrentImpl(ref, lock = mutex.lock)
    }

  // Provides common implementations for derived methods that depend on F being an applicative.
  private[effect] sealed abstract class CommonImpl[F[_], A](
      implicit F: Applicative[F]
  ) extends AtomicCell[F, A] {
    override def modify[B](f: A => (A, B)): F[B] =
      evalModify(a => F.pure(f(a)))

    override def evalUpdate(f: A => F[A]): F[Unit] =
      evalModify(a => f(a).map(aa => (aa, ())))

    override def evalGetAndUpdate(f: A => F[A]): F[A] =
      evalModify(a => f(a).map(aa => (aa, a)))

    override def evalUpdateAndGet(f: A => F[A]): F[A] =
      evalModify(a => f(a).map(aa => (aa, aa)))
  }

  private[effect] final class ConcurrentImpl[F[_], A](
      ref: Ref[F, A],
      lock: Resource[F, Unit]
  )(
      implicit F: Concurrent[F]
  ) extends CommonImpl[F, A] {
    override def get: F[A] =
      ref.get

    override def set(a: A): F[Unit] =
      lock.surround(ref.set(a))

    override def evalModify[B](f: A => F[(A, B)]): F[B] =
      lock.surround {
        ref.get.flatMap(f).flatMap {
          case (a, b) =>
            ref.set(a).as(b)
        }
      }
  }

  private[effect] final class AsyncImpl[F[_], A](
      init: A,
      lock: Resource[F, Unit]
  )(
      implicit F: Async[F]
  ) extends CommonImpl[F, A] {
    @volatile private var cell: A = init

    override def get: F[A] =
      F.delay {
        cell
      }

    override def set(a: A): F[Unit] =
      lock.surround {
        F.delay {
          cell = a
        }
      }

    override def evalModify[B](f: A => F[(A, B)]): F[B] =
      lock.surround {
        F.delay(cell).flatMap(f).flatMap {
          case (a, b) =>
            F.delay {
              cell = a
              b
            }
        }
      }
  }

  /**
   * Allows seeing a `AtomicCell[F, Option[A]]` as a `AtomicCell[F, A]`. This is useful not only
   * for ergonomic reasons, but because some implementations may save space.
   *
   * Setting the `default` value is the same as storing a `None` in the underlying `AtomicCell`.
   */
  def defaultedAtomicCell[F[_], A](
      atomicCell: AtomicCell[F, Option[A]],
      default: A
  )(
      implicit F: Applicative[F]
  ): AtomicCell[F, A] =
    new DefaultedAtomicCell[F, A](atomicCell, default)

  private[effect] final class DefaultedAtomicCell[F[_], A](
      atomicCell: AtomicCell[F, Option[A]],
      default: A
  )(
      implicit F: Applicative[F]
  ) extends CommonImpl[F, A] {
    override def get: F[A] =
      atomicCell.get.map(_.getOrElse(default))

    override def set(a: A): F[Unit] =
      if (a == default) atomicCell.set(None) else atomicCell.set(Some(a))

    override def evalModify[B](f: A => F[(A, B)]): F[B] =
      atomicCell.evalModify { opt =>
        val a = opt.getOrElse(default)
        f(a).map {
          case (result, b) =>
            if (result == default) (None, b) else (Some(result), b)
        }
      }
  }

  implicit def atomicCellOptionSyntax[F[_], A](
      atomicCell: AtomicCell[F, Option[A]]
  )(
      implicit F: Applicative[F]
  ): AtomicCellOptionOps[F, A] =
    new AtomicCellOptionOps(atomicCell)

  final class AtomicCellOptionOps[F[_], A] private[effect] (
      atomicCell: AtomicCell[F, Option[A]]
  )(
      implicit F: Applicative[F]
  ) {
    def getOrElse(default: A): F[A] =
      atomicCell.get.map(_.getOrElse(default))

    def unset: F[Unit] =
      atomicCell.set(None)

    def setValue(a: A): F[Unit] =
      atomicCell.set(Some(a))

    def modifyValueIfSet[B](f: A => (A, B)): F[Option[B]] =
      evalModifyValueIfSet(a => F.pure(f(a)))

    def evalModifyValueIfSet[B](f: A => F[(A, B)]): F[Option[B]] =
      atomicCell.evalModify {
        case None =>
          F.pure((None, None))

        case Some(a) =>
          f(a).map {
            case (result, b) =>
              (Some(result), Some(b))
          }
      }

    def updateValueIfSet(f: A => A): F[Unit] =
      evalUpdateValueIfSet(a => F.pure(f(a)))

    def evalUpdateValueIfSet(f: A => F[A]): F[Unit] =
      atomicCell.evalUpdate {
        case None => F.pure(None)
        case Some(a) => f(a).map(Some.apply)
      }

    def getAndSetValue(a: A): F[Option[A]] =
      atomicCell.getAndSet(Some(a))

    def withDefaultValue(default: A): AtomicCell[F, A] =
      defaultedAtomicCell(atomicCell, default)
  }
}
