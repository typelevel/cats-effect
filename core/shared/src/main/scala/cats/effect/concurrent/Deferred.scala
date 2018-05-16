/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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
  * When created, a `Deferred` is empty. It can then be completed exactly once,
  * and never be made empty again.
  *
  * `get` on an empty `Deferred` will block until the `Deferred` is completed.
  * `get` on a completed `Deferred` will always immediately return its content.
  *
  * `complete(a)` on an empty `Deferred` will set it to `a`, and notify any and
  * all readers currently blocked on a call to `get`.
  * `complete(a)` on a `Deferred` that has already been completed will not modify
  * its content, and result in a failed `F`.
  *
  * Albeit simple, `Deferred` can be used in conjunction with [[Ref]] to build
  * complex concurrent behaviour and data structures like queues and semaphores.
  *
  * Finally, the blocking mentioned above is semantic only, no actual threads are
  * blocked by the implementation.
  */
abstract class Deferred[F[_], A] {

  /**
   * Obtains the value of the `Deferred`, or waits until it has been completed.
   * The returned value may be canceled.
   */
  def get: F[A]

  /**
    * If this `Deferred` is empty, sets the current value to `a`, and notifies
    * any and all readers currently blocked on a `get`.
    *
    * Note that the returned action may complete after the reference has been successfully set:
    * use `F.start(Async.shift *> r.complete)` if you want asynchronous behaviour.
    *
    * If this `Deferred` has already been completed, the returned action immediately fails with
    * an `IllegalStateException`. In the uncommon scenario where this behavior
    * is problematic, you can handle failure explicitly using `attempt` or any other 
    * `ApplicativeError`/`MonadError` combinator on the returned action.
    *
    * Satisfies:
    *   `Deferred[F, A].flatMap(r => r.complete(a) *> r.get) == a.pure[F]`
    */
  def complete(a: A): F[Unit]
}

object Deferred {

  /** Creates an unset promise. **/
  def apply[F[_], A](implicit F: Concurrent[F]): F[Deferred[F, A]] =
    F.delay(unsafe[F, A])

  /**
    * Like `apply` but returns the newly allocated promise directly instead of wrapping it in `F.delay`.
    * This method is considered unsafe because it is not referentially transparent -- it allocates
    * mutable state.
    */
  def unsafe[F[_]: Concurrent, A]: Deferred[F, A] =
    new ConcurrentDeferred[F, A](new AtomicReference(Deferred.State.Unset(LinkedMap.empty)))

  /** Creates an unset promise that only requires an `Async[F]` and does not support cancellation of `get`. **/
  def uncancelable[F[_], A](implicit F: Async[F]): F[Deferred[F, A]] =
    F.delay(unsafeUncancelable[F, A])

  /**
    * Like `async` but returns the newly allocated promise directly instead of wrapping it in `F.delay`.
    * This method is considered unsafe because it is not referentially transparent -- it allocates
    * mutable state.
    */
  def unsafeUncancelable[F[_]: Async, A]: Deferred[F, A] =
    new AsyncDeferred[F, A](Promise[A]())

  private final class Id

  private sealed abstract class State[A]
  private object State {
    final case class Set[A](a: A) extends State[A]
    final case class Unset[A](waiting: LinkedMap[Id, A => Unit]) extends State[A]
  }

  private final class ConcurrentDeferred[F[_], A](ref: AtomicReference[State[A]])(implicit F: Concurrent[F]) extends Deferred[F, A] {

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
          case s @ State.Set(_) => throw new IllegalStateException("Attempting to complete a Deferred that has already been completed")
          case s @ State.Unset(_) =>
            if (ref.compareAndSet(s, State.Set(a))) notifyReaders(s)
            else loop()
        }

      F.delay(loop())
    }
  }

  private final class AsyncDeferred[F[_], A](p: Promise[A])(implicit F: Async[F]) extends Deferred[F, A] {

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
