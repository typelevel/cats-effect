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

import cats.effect.internals.{LinkedMap, TrampolineEC}
import cats.implicits.{catsSyntaxEither => _, _}

import java.util.concurrent.atomic.AtomicReference

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Promise}
import scala.util.{Failure, Success}

/**
  * A purely functional synchronization primitive which represents a single value
  * which may not yet be available.
  *
  * When created, a `Pledge` is empty. It can then be completed exactly once,
  * and never be made empty again.
  *
  * `get` on an empty `Pledge` will block until the `Pledge` is completed.
  * `get` on a completed `Pledge` will always immediately return its content.
  *
  * `complete(a)` on an empty `Pledge` will set it to `a`, and notify any and
  * all readers currently blocked on a call to `get`.
  * `complete(a)` on a `Pledge` that has already been completed will not modify
  * its content, and result in a failed `F`.
  *
  * Albeit simple, `Pledge` can be used in conjunction with [[Ref]] to build
  * complex concurrent behaviour and data structures like queues and semaphores.
  *
  * Finally, the blocking mentioned above is semantic only, no actual threads are
  * blocked by the implementation.
  */
abstract class Pledge[F[_], A] {

  /**
   * Obtains the value of the `Pledge`, or waits until it has been completed.
   * The returned value may be canceled.
   */
  def get: F[A]

  /**
    * If this `Pledge` is empty, *synchronously* sets the current value to `a`, and notifies
    * any and all readers currently blocked on a `get`.
    *
    * Note that the returned action completes after the reference has been successfully set:
    * use `async.shiftStart(r.complete)` if you want asynchronous behaviour.
    *
    * If this `Pledge` has already been completed, the returned action immediately fails with
    * an `IllegalStateException`. In the uncommon scenario where this behavior
    * is problematic, you can handle failure explicitly using `attempt` or any other 
    * `ApplicativeError`/`MonadError` combinator on the returned action.
    *
    * Satisfies:
    *   `Pledge[F, A].flatMap(r => r.complete(a) *> r.get) == a.pure[F]`
    */
  def complete(a: A): F[Unit]
}

object Pledge {

  /** Creates an unset promise. **/
  def apply[F[_], A](implicit F: Concurrent[F]): F[Pledge[F, A]] =
    F.delay(unsafe[F, A])

  /**
    * Like `apply` but returns the newly allocated promise directly instead of wrapping it in `F.delay`.
    * This method is considered unsafe because it is not referentially transparent -- it allocates
    * mutable state.
    */
  def unsafe[F[_]: Concurrent, A]: Pledge[F, A] =
    new ConcurrentPledge[F, A](new AtomicReference(Pledge.State.Unset(LinkedMap.empty)))
    
  /** Creates an unset promise that only requires an `Async[F]` and does not support cancelation of `get`. **/
  def async[F[_], A](implicit F: Async[F]): F[Pledge[F, A]] =
    F.delay(unsafeAsync[F, A])

  /**
    * Like `async` but returns the newly allocated promise directly instead of wrapping it in `F.delay`.
    * This method is considered unsafe because it is not referentially transparent -- it allocates
    * mutable state.
    */
  def unsafeAsync[F[_]: Async, A]: Pledge[F, A] =
    new AsyncPledge[F, A](Promise[A]())

  private final class Id

  private sealed abstract class State[A]
  private object State {
    final case class Set[A](a: A) extends State[A]
    final case class Unset[A](waiting: LinkedMap[Id, A => Unit]) extends State[A]
  }

  private final class ConcurrentPledge[F[_], A](ref: AtomicReference[State[A]])(implicit F: Concurrent[F]) extends Pledge[F, A] {

    def get: F[A] =
      F.delay(ref.get).flatMap {
        case State.Set(a) => F.pure(a)
        case State.Unset(_) =>
          F.cancelable { cb =>
            val id = unsafeRegister(cb)
            @tailrec
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

    private[this] def unsafeRegister(cb: Either[Throwable, A] => Unit): Id = {
      val id = new Id

      @tailrec
      def register(): Option[A] =
        ref.get match {
          case State.Set(a) => Some(a)
          case s @ State.Unset(waiting) =>
            val updated = State.Unset(waiting.updated(id, (a: A) => cb(Right(a))))
            if (ref.compareAndSet(s, updated)) None
            else register()
        }

      register.foreach(a => cb(Right(a)))

      id
    }

    def complete(a: A): F[Unit] = {
      def notifyReaders(r: State.Unset[A]): Unit =
        r.waiting.values.foreach { cb =>
          cb(a)
        }

      @tailrec
      def loop(): Unit =
        ref.get match {
          case s @ State.Set(_) => throw new IllegalStateException("Attempting to complete a Pledge that has already been completed")
          case s @ State.Unset(_) =>
            if (ref.compareAndSet(s, State.Set(a))) notifyReaders(s)
            else loop()
        }

      F.delay(loop())
    }
  }

  private final class AsyncPledge[F[_], A](p: Promise[A])(implicit F: Async[F]) extends Pledge[F, A] {

    def get: F[A] =
      F.async { cb =>
        implicit val ec: ExecutionContext = TrampolineEC.immediate
        p.future.onComplete {
          case Success(a) => cb(Right(a))
          case Failure(t) => cb(Left(t))
        }
      }

    def complete(a: A): F[Unit] =
      F.delay(p.success(a))
  }
}
