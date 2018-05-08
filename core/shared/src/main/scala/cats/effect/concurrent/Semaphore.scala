/*
 * The MIT License (MIT)
 * 
 * Copyright (c) 2013-2018 Paul Chiusano, and respective contributors 
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package cats
package effect
package concurrent

import cats.effect.internals.Canceled
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
   * Use `[[tryAcquire]]` or `[[tryAcquireBy]]` if you wish to attempt an
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
}

object Semaphore {

  private def assertNonNegative[F[_]](n: Long)(implicit F: Sync[F]): F[Unit] =
    F.delay(assert(n >= 0, s"n must be nonnegative, was: $n"))

  // A semaphore is either empty, and there are number of outstanding acquires (Left)
  // or it is non-empty, and there are n permits available (Right)
  private type State[F[_]] = Either[Queue[(Long, Deferred[F, Unit])], Long]

  /** Creates a new `Semaphore`, initialized with `n` available permits. */
  def apply[F[_]](n: Long)(implicit F: Concurrent[F]): F[Semaphore[F]] = {
    assertNonNegative(n) *>
      Ref[F, State[F]](Right(n)).map(stateRef => new ConcurrentSemaphore(stateRef))
  }

  /**
   * Like [[apply]] but only requires an `Async` constraint in exchange for the various
   * acquire effects being uncancelable.
   */
  def async[F[_]](n: Long)(implicit F: Async[F]): F[Semaphore[F]] = {
    assertNonNegative(n) *>
      Ref[F, State[F]](Right(n)).map(stateRef => new AsyncSemaphore(stateRef))
  }
  
  private abstract class AbstractSemaphore[F[_]](state: Ref[F, State[F]])(implicit F: Async[F]) extends Semaphore[F] {
    protected def mkGate: F[Deferred[F, Unit]]
    protected def awaitGate(entry: (Long, Deferred[F, Unit])): F[Unit]

    private def open(gate: Deferred[F, Unit]): F[Unit] = gate.complete(())

    def count = state.get.map(count_)

    private def count_(s: State[F]): Long =
      s.fold(ws => -ws.map(_._1).sum, identity)

    def acquireN(n: Long) = {
      assertNonNegative(n) *> {
        if (n == 0) F.unit
        else mkGate.flatMap { gate =>
          state
            .modifyAndReturn { old =>
              val u = old match {
                case Left(waiting) => Left(waiting :+ (n -> gate))
                case Right(m) =>
                  if (n <= m) Right(m - n)
                  else Left(Queue((n - m) -> gate))
              }
              (u, u)
            }
            .flatMap { 
              case Left(waiting) =>
                val entry = waiting.lastOption.getOrElse(sys.error("Semaphore has empty waiting queue rather than 0 count"))
                awaitGate(entry)
                
              case Right(_) => F.unit
            }
        }
      }
    }

    def tryAcquireN(n: Long) = {
      assertNonNegative(n) *> {
        if (n == 0) F.pure(true)
        else
          state
            .modifyAndReturn { old =>
              val u = old match {
                case Right(m) if m >= n => Right(m - n)
                case w                  => w
              }
              (u, (old, u))
            }
            .map { case (previous, now) =>
              now.fold(_ => false, n => previous.fold(_ => false, m => n != m))
            }
      }
    }

    def releaseN(n: Long) = {
      assertNonNegative(n) *> {
        if (n == 0) F.pure(())
        else
          state
            .modifyAndReturn { old =>
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
            .flatMap { case (previous, now) =>
              // invariant: count_(now) == count_(previous) + n
              previous match {
                case Left(waiting) =>
                  // now compare old and new sizes to figure out which actions to run
                  val newSize = now.fold(_.size, _ => 0)
                  val released = waiting.size - newSize
                  waiting.take(released).foldRight(F.pure(())) { (hd, tl) =>
                    open(hd._2) *> tl
                  }
                case Right(_) => F.pure(())
              }
            }
      }
    }

    def available: F[Long] = state.get.map {
      case Left(_)  => 0
      case Right(n) => n
    }

    def withPermit[A](t: F[A]): F[A] =
      F.bracket(acquire)(_ => t)(_ => release)
  }

  private final class ConcurrentSemaphore[F[_]](state: Ref[F, State[F]])(implicit F: Concurrent[F]) extends AbstractSemaphore(state) {
    protected def mkGate: F[Deferred[F, Unit]] = Deferred[F, Unit]
    protected def awaitGate(entry: (Long, Deferred[F, Unit])): F[Unit] =
      F.onCancelRaiseError(entry._2.get, Canceled).recoverWith {
        case Canceled =>
          state.modify {
            case Left(waiting) => Left(waiting.filter(_ != entry))
            case Right(m)      => Right(m)
          } *> F.async[Unit](cb => ())
      }
  }

  private final class AsyncSemaphore[F[_]](state: Ref[F, State[F]])(implicit F: Async[F]) extends AbstractSemaphore(state) {
    protected def mkGate: F[Deferred[F, Unit]] = Deferred.async[F, Unit]
    protected def awaitGate(entry: (Long, Deferred[F, Unit])): F[Unit] = entry._2.get
  }
}
