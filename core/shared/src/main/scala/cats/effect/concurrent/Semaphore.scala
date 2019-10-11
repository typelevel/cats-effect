/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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
package concurrent

import cats.effect.concurrent.Semaphore.TransformedSemaphore
import cats.implicits._

import scala.collection.immutable.Queue

/**
 * A purely functional semaphore.
 *
 * A semaphore has a non-negative number of permits available. Acquiring a permit
 * decrements the current number of permits and releasing a permit increases
 * the current number of permits. An acquire that occurs when there are no
 * permits available results in semantic blocking until a permit becomes available.
 *
 * Blocking acquires are cancelable if the semaphore is created with `Semaphore.apply`
 * (and hence, with a `Concurrent[F]` instance). Blocking acquires are non-cancelable
 * if the semaphore is created with `Semaphore.async` (and hence, with an `Async[F]` instance).
 */
abstract class Semaphore[F[_]] {

  /**
   * Returns the number of permits currently available. Always non-negative.
   *
   * May be out of date the instant after it is retrieved.
   * Use `[[tryAcquire]]` or `[[tryAcquireN]]` if you wish to attempt an
   * acquire, returning immediately if the current count is not high enough
   * to satisfy the request.
   */
  def available: F[Long]

  /**
   * Obtains a snapshot of the current count. May be negative.
   *
   * Like [[available]] when permits are available but returns the number of permits
   * callers are waiting for when there are no permits available.
   */
  def count: F[Long]

  /**
   * Acquires `n` permits.
   *
   * The returned effect semantically blocks until all requested permits are
   * available. Note that acquires are statisfied in strict FIFO order, so given
   * `s: Semaphore[F]` with 2 permits available, an `acquireN(3)` will
   * always be satisfied before a later call to `acquireN(1)`.
   *
   * @param n number of permits to acquire - must be >= 0
   */
  def acquireN(n: Long): F[Unit]

  /** Acquires a single permit. Alias for `[[acquireN]](1)`. */
  def acquire: F[Unit] = acquireN(1)

  /**
   * Acquires `n` permits now and returns `true`, or returns `false` immediately. Error if `n < 0`.
   *
   * @param n number of permits to acquire - must be >= 0
   */
  def tryAcquireN(n: Long): F[Boolean]

  /** Alias for `[[tryAcquireN]](1)`. */
  def tryAcquire: F[Boolean] = tryAcquireN(1)

  /**
   * Releases `n` permits, potentially unblocking up to `n` outstanding acquires.
   *
   * @param n number of permits to release - must be >= 0
   */
  def releaseN(n: Long): F[Unit]

  /** Releases a single permit. Alias for `[[releaseN]](1)`. */
  def release: F[Unit] = releaseN(1)

  /**
   * Returns an effect that acquires a permit, runs the supplied effect, and then releases the permit.
   */
  def withPermit[A](t: F[A]): F[A]

  /**
   * Modify the context `F` using natural isomorphism  `f` with `g`.
   */
  def imapK[G[_]](f: F ~> G, g: G ~> F): Semaphore[G] =
    new TransformedSemaphore(this, f, g)
}

object Semaphore {

  /**
   * Creates a new `Semaphore`, initialized with `n` available permits.
   */
  def apply[F[_]](n: Long)(implicit F: Concurrent[F]): F[Semaphore[F]] =
    assertNonNegative[F](n) *>
      Ref.of[F, State[F]](Right(n)).map(stateRef => new ConcurrentSemaphore(stateRef))

  /**
   * Like [[apply]] but only requires an `Async` constraint in exchange for the various
   * acquire effects being uncancelable.
   *
   * WARN: some `Async` data types, like [[IO]], can be cancelable,
   * making `uncancelable` values unsafe. Such values are only useful
   * for optimization purposes, in cases where the use case does not
   * require cancellation or in cases in which an `F[_]` data type
   * that does not support cancellation is used.
   */
  def uncancelable[F[_]](n: Long)(implicit F: Async[F]): F[Semaphore[F]] =
    assertNonNegative[F](n) *>
      Ref.of[F, State[F]](Right(n)).map(stateRef => new AsyncSemaphore(stateRef))

  /**
   * Creates a new `Semaphore`, initialized with `n` available permits.
   * like `apply` but initializes state using another effect constructor
   */
  def in[F[_], G[_]](n: Long)(implicit F: Sync[F], G: Concurrent[G]): F[Semaphore[G]] =
    assertNonNegative[F](n) *>
      Ref.in[F, G, State[G]](Right(n)).map(stateRef => new ConcurrentSemaphore(stateRef))

  /**
   * Creates a new `Semaphore`, initialized with `n` available permits.
   * Like [[apply]] but only requires an `Async` constraint in exchange for the various
   * acquire effects being uncancelable
   * and initializes state using another effect constructor
   */
  def uncancelableIn[F[_], G[_]](n: Long)(implicit F: Sync[F], G: Async[G]): F[Semaphore[G]] =
    assertNonNegative[F](n) *>
      Ref.in[F, G, State[G]](Right(n)).map(stateRef => new AsyncSemaphore(stateRef))

  private def assertNonNegative[F[_]](n: Long)(implicit F: ApplicativeError[F, Throwable]): F[Unit] =
    if (n < 0) F.raiseError(new IllegalArgumentException(s"n must be nonnegative, was: $n")) else F.unit

  // A semaphore is either empty, and there are number of outstanding acquires (Left)
  // or it is non-empty, and there are n permits available (Right)
  private type State[F[_]] = Either[Queue[(Long, Deferred[F, Unit])], Long]

  abstract private class AbstractSemaphore[F[_]](state: Ref[F, State[F]])(implicit F: Async[F]) extends Semaphore[F] {
    protected def mkGate: F[Deferred[F, Unit]]

    private def open(gate: Deferred[F, Unit]): F[Unit] = gate.complete(())

    def count: F[Long] = state.get.map(count_)

    private def count_(s: State[F]): Long = s match {
      case Left(waiting)    => -waiting.map(_._1).sum
      case Right(available) => available
    }

    def acquireN(n: Long): F[Unit] = F.bracketCase(acquireNInternal(n)) { case (g, _) => g } {
      case ((_, c), ExitCase.Canceled) => c
      case _                           => F.unit
    }

    def acquireNInternal(n: Long): F[(F[Unit], F[Unit])] =
      assertNonNegative[F](n) *> {
        if (n == 0) F.pure((F.unit, F.unit))
        else {
          mkGate.flatMap { gate =>
            state
              .modify { old =>
                val u = old match {
                  case Left(waiting) => Left(waiting :+ (n -> gate))
                  case Right(m) =>
                    if (n <= m) Right(m - n)
                    else Left(Queue((n - m) -> gate))
                }
                (u, u)
              }
              .map {
                case Left(waiting) =>
                  val cleanup = state.modify {
                    case Left(waiting) =>
                      waiting.find(_._2 eq gate).map(_._1) match {
                        case None    => (Left(waiting), releaseN(n))
                        case Some(m) => (Left(waiting.filterNot(_._2 eq gate)), releaseN(n - m))
                      }
                    case Right(m) => (Right(m + n), F.unit)
                  }.flatten

                  val entry =
                    waiting.lastOption.getOrElse(sys.error("Semaphore has empty waiting queue rather than 0 count"))

                  entry._2.get -> cleanup

                case Right(_) => F.unit -> releaseN(n)
              }
          }
        }
      }

    def tryAcquireN(n: Long): F[Boolean] =
      assertNonNegative[F](n) *> {
        if (n == 0) F.pure(true)
        else
          state
            .modify { old =>
              val u = old match {
                case Right(m) if m >= n => Right(m - n)
                case w                  => w
              }
              (u, (old, u))
            }
            .map {
              case (previous, now) =>
                now match {
                  case Left(_) => false
                  case Right(n) =>
                    previous match {
                      case Left(_)  => false
                      case Right(m) => n != m
                    }
                }
            }
      }

    def releaseN(n: Long): F[Unit] =
      assertNonNegative[F](n) *> {
        if (n == 0) F.unit
        else
          state
            .modify { old =>
              val u = old match {
                case Left(waiting) =>
                  // just figure out how many to strip from waiting queue,
                  // but don't run anything here inside the modify
                  var m = n
                  var waiting2 = waiting
                  while (waiting2.nonEmpty && m > 0) {
                    val (k, gate) = waiting2.head
                    if (k > m) {
                      waiting2 = (k - m, gate) +: waiting2.tail
                      m = 0
                    } else {
                      m -= k
                      waiting2 = waiting2.tail
                    }
                  }
                  if (waiting2.nonEmpty) Left(waiting2)
                  else Right(m)
                case Right(m) => Right(m + n)
              }
              (u, (old, u))
            }
            .flatMap {
              case (previous, now) =>
                // invariant: count_(now) == count_(previous) + n
                previous match {
                  case Left(waiting) =>
                    // now compare old and new sizes to figure out which actions to run
                    val newSize = now match {
                      case Left(w)  => w.size
                      case Right(_) => 0
                    }
                    val released = waiting.size - newSize
                    waiting.take(released).foldRight(F.unit) { (hd, tl) =>
                      open(hd._2) *> tl
                    }
                  case Right(_) => F.unit
                }
            }
      }

    def available: F[Long] = state.get.map {
      case Left(_)  => 0
      case Right(n) => n
    }

    def withPermit[A](t: F[A]): F[A] =
      F.bracket(acquireNInternal(1)) { case (g, _) => g *> t } { case (_, c) => c }
  }

  final private class ConcurrentSemaphore[F[_]](state: Ref[F, State[F]])(implicit F: Concurrent[F])
      extends AbstractSemaphore(state) {
    protected def mkGate: F[Deferred[F, Unit]] = Deferred[F, Unit]
  }

  final private class AsyncSemaphore[F[_]](state: Ref[F, State[F]])(implicit F: Async[F])
      extends AbstractSemaphore(state) {
    protected def mkGate: F[Deferred[F, Unit]] = Deferred.uncancelable[F, Unit]
  }

  final private[concurrent] class TransformedSemaphore[F[_], G[_]](
    underlying: Semaphore[F],
    trans: F ~> G,
    inverse: G ~> F
  ) extends Semaphore[G] {
    override def available: G[Long] = trans(underlying.available)
    override def count: G[Long] = trans(underlying.count)
    override def acquireN(n: Long): G[Unit] = trans(underlying.acquireN(n))
    override def tryAcquireN(n: Long): G[Boolean] = trans(underlying.tryAcquireN(n))
    override def releaseN(n: Long): G[Unit] = trans(underlying.releaseN(n))
    override def withPermit[A](t: G[A]): G[A] = trans(underlying.withPermit(inverse(t)))
  }
}
