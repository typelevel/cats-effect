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
 * A semaphore has a non-negative number of permits available. Decrementing the semaphore
 * acquires a permit and incrementing the semaphore releases a permit. A decrement
 * that occurs when there are no permits available results in semantic blocking until
 * a permit is available.
 *
 * Blocking decrements are cancelable if the semaphore is created with `Semaphore.apply`
 * (and hence, with a `Concurrent[F]` instance). Blocking decrements are non-cancelable
 * if the semaphore is created with `Semaphore.async` (and hence, with an `Async[F]` instance).
 */
abstract class Semaphore[F[_]] {

  /**
   * Returns the number of permits currently available. Always non-negative.
   *
   * May be out of date the instant after it is retrieved.
   * Use `[[tryDecrement]]` or `[[tryDecrementBy]]` if you wish to attempt a 
   * decrement and return immediately if the current count is not high enough
   * to satisfy the request.
   */
  def available: F[Long]

  /**
   * Obtains a snapshot of the current count. May be negative.
   *
   * Like [[available]] when permits are available but returns the number of permits
   * callers are blocking on when there are no permits available.
   */
  def count: F[Long]

  /**
   * Decrements the number of available permits by `n`, blocking until `n`
   * are available. Error if `n < 0`. The blocking is semantic; we do not
   * literally block a thread waiting for permits to become available.
   * Note that decrements are satisfied in strict FIFO order, so given
   * `s: Semaphore[F]` with 2 permits available, a `decrementBy(3)` will
   * always be satisfied before a later call to `decrementBy(1)`.
   */
  def decrementBy(n: Long): F[Unit]

  /** Decrements the number of permits by 1. Alias for `[[decrementBy]](1)`. */
  def decrement: F[Unit] = decrementBy(1)

  /** Acquires `n` permits now and returns `true`, or returns `false` immediately. Error if `n < 0`. */
  def tryDecrementBy(n: Long): F[Boolean]

  /** Alias for `[[tryDecrementBy]](1)`. */
  def tryDecrement: F[Boolean] = tryDecrementBy(1)

  /**
   * Increments the number of available permits by `n`. Error if `n < 0`.
   * This will have the effect of unblocking `n` acquisitions.
   */
  def incrementBy(n: Long): F[Unit]

  /** Increments the number of permits by 1. Alias for `[[incrementBy]](1)`. */
  def increment: F[Unit] = incrementBy(1)

  /**
   * Resets the count of this semaphore back to zero, and returns the previous count.
   * Throws an `IllegalArgumentException` if count is below zero (due to pending
   * decrements).
   */
  def clear: F[Long]

  /**
   * Returns a task that acquires a permit, runs the supplied task, and then releases the permit.
   */
  def withPermit[A](t: F[A]): F[A]
}

object Semaphore {

  private def assertNonNegative(n: Long) = assert(n >= 0, s"n must be nonnegative, was: $n")

  // semaphore is either empty, and there are number of outstanding acquires (Left)
  // or it is non-empty, and there are n permits available (Right)
  private type State[F[_]] = Either[Queue[(Long, Deferred[F, Unit])], Long]

  /** Creates a new `Semaphore`, initialized with `n` available permits. */
  def apply[F[_]](n: Long)(implicit F: Concurrent[F]): F[Semaphore[F]] = {
    assertNonNegative(n)
    Ref[F, State[F]](Right(n)).map(stateRef => new ConcurrentSemaphore(stateRef))
  }

  /**
   * Like [[apply]] but only requires an `Async` constraint at the cost of the various
   * decrement functions being uncancelable.
   */
  def async[F[_]](n: Long)(implicit F: Async[F]): F[Semaphore[F]] = {
    assertNonNegative(n)
    Ref[F, State[F]](Right(n)).map(stateRef => new AsyncSemaphore(stateRef))
  }
  
  private abstract class AbstractSemaphore[F[_]](state: Ref[F, State[F]])(implicit F: Async[F]) extends Semaphore[F] {
    protected def mkGate: F[Deferred[F, Unit]]
    protected def awaitGate(entry: (Long, Deferred[F, Unit])): F[Unit]

    private def open(gate: Deferred[F, Unit]): F[Unit] = gate.complete(())

    def count = state.get.map(count_)

    private def count_(s: State[F]): Long =
      s.fold(ws => -ws.map(_._1).sum, identity)

    def decrementBy(n: Long) = {
      assertNonNegative(n)
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

    def tryDecrementBy(n: Long) = {
      assertNonNegative(n)
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

    def clear: F[Long] =
      state
        .modifyAndReturn { old =>
          val u = old match {
            case Left(e) =>
              throw new IllegalStateException("cannot clear a semaphore with negative count")
            case Right(n) => Right(0L)
          }
          (u, old)
        }
        .flatMap { 
          case Right(n) => F.pure(n)
          case Left(_)  => sys.error("impossible, exception thrown above")
        }

    def incrementBy(n: Long) = {
      assertNonNegative(n)
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

    def available = state.get.map {
      case Left(_)  => 0
      case Right(n) => n
    }

    def withPermit[A](t: F[A]): F[A] =
      F.bracket(decrement)(_ => t)(_ => increment)
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
