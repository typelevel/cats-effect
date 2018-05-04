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

import cats.effect.internals.LinkedMap
import cats.implicits.{catsSyntaxEither => _, _}

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec

/**
  * A purely functional synchronization primitive which represents a single value
  * which may not yet be available.
  *
  * When created, a `Promise` is empty. It can then be completed exactly once,
  * and never be made empty again.
  *
  * `get` on an empty `Promise` will block until the `Promise` is completed.
  * `get` on a completed `Promise` will always immediately return its content.
  *
  * `complete(a)` on an empty `Promise` will set it to `a`, and notify any and
  * all readers currently blocked on a call to `get`.
  * `complete(a)` on a `Promise` that has already been completed will not modify
  * its content, and result in a failed `F`.
  *
  * Albeit simple, `Promise` can be used in conjunction with [[Ref]] to build
  * complex concurrent behaviour and data structures like queues and semaphores.
  *
  * Finally, the blocking mentioned above is semantic only, no actual threads are
  * blocked by the implementation.
  */
abstract class Promise[F[_], A] {

  /**
   * Obtains the value of the `Promise`, or waits until it has been completed.
   * The returned value may be canceled.
   */
  def get: F[A]

  /**
    * If this `Promise` is empty, *synchronously* sets the current value to `a`, and notifies
    * any and all readers currently blocked on a `get`.
    *
    * Note that the returned action completes after the reference has been successfully set:
    * use `async.shiftStart(r.complete)` if you want asynchronous behaviour.
    *
    * If this `Promise` has already been completed, the returned action immediately fails with
    * a [[Promise.AlreadyCompletedException]]. In the uncommon scenario where this behavior
    * is problematic, you can handle failure explicitly using `attempt` or any other 
    * `ApplicativeError`/`MonadError` combinator on the returned action.
    *
    * Satisfies:
    *   `Promise.empty[F, A].flatMap(r => r.complete(a) *> r.get) == a.pure[F]`
    */
  def complete(a: A): F[Unit]
}

object Promise {

  /** Creates an unset promise. **/
  def empty[F[_], A](implicit F: Concurrent[F]): F[Promise[F, A]] =
    F.delay(unsafeEmpty[F, A])

  /**
    * Like `empty` but returns the newly allocated promise directly instead of wrapping it in `F.delay`.
    * This method is considered unsafe because it is not referentially transparent -- it allocates
    * mutable state.
    */
  def unsafeEmpty[F[_]: Concurrent, A]: Promise[F, A] =
    new ConcurrentPromise[F, A](new AtomicReference(Promise.State.Unset(LinkedMap.empty)))

  /** Raised when trying to complete a [[Promise]] that has already been completed. */
  final class AlreadyCompletedException extends Throwable(
    "Trying to complete a Promise that has already been completed"
  )

  private final class Id

  private sealed abstract class State[A]
  private object State {
    final case class Set[A](a: A) extends State[A]
    final case class Unset[A](waiting: LinkedMap[Id, A => Unit]) extends State[A]
  }

  private final class ConcurrentPromise[F[_], A] (ref: AtomicReference[State[A]])(implicit F: Concurrent[F]) extends Promise[F, A] {

    def get: F[A] =
      F.delay(ref.get).flatMap {
        case State.Set(a) => F.pure(a)
        case State.Unset(_) =>
          F.cancelable { cb =>
            val id = new Id

            def register: Option[A] =
              ref.get match {
                case State.Set(a) => Some(a)
                case s @ State.Unset(waiting) =>
                  val updated = State.Unset(waiting.updated(id, (a: A) => cb(Right(a))))
                  if (ref.compareAndSet(s, updated)) None
                  else register
              }

            register.foreach(a => cb(Right(a)))

            def unregister(): Unit =
              ref.get match {
                case State.Set(_) => ()
                case s @ State.Unset(waiting) =>
                  val updated = State.Unset(waiting - id)
                  if (ref.compareAndSet(s, updated)) ()
                  else unregister()
              }
            IO(unregister())
          }
      }

    def complete(a: A): F[Unit] = {
      def notifyReaders(r: State.Unset[A]): Unit =
        r.waiting.values.foreach { cb =>
          cb(a)
        }

      @tailrec
      def loop(): Unit =
        ref.get match {
          case s @ State.Set(_) => throw new AlreadyCompletedException
          case s @ State.Unset(_) =>
            if (ref.compareAndSet(s, State.Set(a))) notifyReaders(s)
            else loop()
        }

      F.delay(loop())
    }
  }
}
