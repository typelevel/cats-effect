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

import simulacrum._
import cats.data.{EitherT, OptionT, StateT, WriterT}
import cats.effect.IO.{Delay, Pure, RaiseError}
import cats.effect.internals.IORunLoop
import cats.syntax.all._

import scala.annotation.implicitNotFound
import scala.util.Either

/**
 * Type class for [[Async]] data types that are cancelable and
 * can be started concurrently.
 *
 * Thus this type class allows abstracting over data types that:
 *
 *  1. implement the [[Async]] algebra, with all its restrictions
 *  1. can provide logic for cancellation, to be used in race
 *     conditions in order to release resources early
 *     (in its [[Concurrent!.cancelable cancelable]] builder)
 *
 * Due to these restrictions, this type class also affords to describe
 * a [[Concurrent!.start start]] operation that can start async
 * processing, suspended in the context of `F[_]` and that can be
 * cancelled or joined.
 *
 * Without cancellation being baked in, we couldn't afford to do it.
 * See below.
 *
 * ==Cancelable builder==
 *
 * Therefore the signature exposed by the
 * [[Concurrent!.cancelable cancelable]] builder is this:
 *
 * {{{
 *   (Either[Throwable, A] => Unit) => F[Unit]
 * }}}
 *
 * `F[Unit]` is used to represent a cancellation action which will
 * send a signal to the producer, that may observe it and cancel the
 * asynchronous process.
 *
 * ==On Cancellation==
 *
 * Simple asynchronous processes, like Scala's `Future`, can be
 * described with this very basic and side-effectful type and you
 * should recognize what is more or less the signature of
 * `Future#onComplete` or of [[Async.async]] (minus the error
 * handling):
 *
 * {{{
 *   (A => Unit) => Unit
 * }}}
 *
 * But many times the abstractions built to deal with asynchronous
 * tasks can also provide a way to cancel such processes, to be used
 * in race conditions in order to cleanup resources early, so a very
 * basic and side-effectful definition of asynchronous processes that
 * can be cancelled would be:
 *
 * {{{
 *   (A => Unit) => Cancelable
 * }}}
 *
 * This is approximately the signature of JavaScript's `setTimeout`,
 * which will return a "task ID" that can be used to cancel it. Or of
 * Java's `ScheduledExecutorService#schedule`, which will return a
 * Java `ScheduledFuture` that has a `.cancel()` operation on it.
 *
 * Similarly, for `Concurrent` data types, we can provide
 * cancellation logic, that can be triggered in race conditions to
 * cancel the on-going processing, only that `Concurrent`'s
 * cancelable token is an action suspended in an `IO[Unit]`. See
 * [[IO.cancelable]].
 *
 * Suppose you want to describe a "sleep" operation, like that described
 * by [[Timer]] to mirror Java's `ScheduledExecutorService.schedule`
 * or JavaScript's `setTimeout`:
 *
 * {{{
 *   def sleep(d: FiniteDuration): F[Unit]
 * }}}
 *
 * This signature is in fact incomplete for data types that are not
 * cancelable, because such equivalent operations always return some
 * cancellation token that can be used to trigger a forceful
 * interruption of the timer. This is not a normal "dispose" or
 * "finally" clause in a try/catch block, because "cancel" in the
 * context of an asynchronous process is ''concurrent'' with the
 * task's own run-loop.
 *
 * To understand what this means, consider that in the case of our
 * `sleep` as described above, on cancellation we'd need a way to
 * signal to the underlying `ScheduledExecutorService` to forcefully
 * remove the scheduled `Runnable` from its internal queue of
 * scheduled tasks, ''before'' its execution. Therefore, without a
 * cancelable data type, a safe signature needs to return a
 * cancellation token, so it would look like this:
 *
 * {{{
 *   def sleep(d: FiniteDuration): F[(F[Unit], F[Unit])]
 * }}}
 *
 * This function is returning a tuple, with one `F[Unit]` to wait for
 * the completion of our sleep and a second `F[Unit]` to cancel the
 * scheduled computation in case we need it. This is in fact the shape
 * of [[Fiber]]'s API. And this is exactly what the
 * [[Concurrent!.start start]] operation returns.
 *
 * The difference between a [[Concurrent]] data type and one that
 * is only [[Async]] is that you can go from any `F[A]` to a
 * `F[Fiber[F, A]]`, to participate in race conditions and that can be
 * cancelled should the need arise, in order to trigger an early
 * release of allocated resources.
 *
 * Thus a [[Concurrent]] data type can safely participate in race
 * conditions, whereas a data type that is only [[Async]] cannot do it
 * without exposing and forcing the user to work with cancellation
 * tokens. An [[Async]] data type cannot expose for example a `start`
 * operation that is safe.
 */
@typeclass
@implicitNotFound("""Cannot find implicit value for Concurrent[${F}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Scheduler or some equivalent type.""")
trait Concurrent[F[_]] extends Async[F] {
  /**
   * Creates a cancelable `F[A]` instance that executes an
   * asynchronous process on evaluation.
   *
   * This builder accepts a registration function that is
   * being injected with a side-effectful callback, to be called
   * when the asynchronous process is complete with a final result.
   *
   * The registration function is also supposed to return
   * an `IO[Unit]` that captures the logic necessary for
   * cancelling the asynchronous process, for as long as it
   * is still active.
   *
   * Example:
   *
   * {{{
   *   import java.util.concurrent.ScheduledExecutorService
   *   import scala.concurrent.duration._
   *
   *   def sleep[F[_]](d: FiniteDuration)
   *     (implicit F: Concurrent[F], ec: ScheduledExecutorService): F[A] = {
   *
   *     F.cancelable { cb =>
   *       // Note the callback is pure, so we need to trigger evaluation
   *       val run = new Runnable { def run() = cb(Right(())).unsafeRunSync }
   *
   *       // Schedules task to run after delay
   *       val future = ec.schedule(run, d.length, d.unit)
   *
   *       // Cancellation logic, suspended in IO
   *       IO(future.cancel())
   *     }
   *   }
   * }}}
   */
  def cancelable[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): F[A]

  /**
   * Start concurrent execution of the source suspended in
   * the `F` context.
   *
   * Returns a [[Fiber]] that can be used to either join or cancel
   * the running computation, being similar in spirit (but not
   * in implementation) to starting a thread.
   */
  def start[A](fa: F[A]): F[Fiber[F, A]]

  /**
   * Inherited from [[LiftIO]], defines a conversion from [[IO]]
   * in terms of the `Concurrent` type class.
   *
   * N.B. expressing this conversion in terms of `Concurrent` and
   * its capabilities means that the resulting `F` is cancelable in
   * case the source `IO` is.
   *
   * To access this implementation as a standalone function, you can
   * use [[Concurrent$.liftIO Concurrent.liftIO]]
   * (on the object companion).
   */
  override def liftIO[A](ioa: IO[A]): F[A] =
    Concurrent.liftIO(ioa)(this)
}

object Concurrent {
  /**
   * Lifts any `IO` value into any data type implementing [[Concurrent]].
   *
   * Compared with [[Async.liftIO]], this version preserves the
   * interruptibility of the given `IO` value.
   *
   * This is the default `Concurrent.liftIO` implementation.
   */
  def liftIO[F[_], A](ioa: IO[A])(implicit F: Concurrent[F]): F[A] =
    ioa match {
      case Pure(a) => F.pure(a)
      case RaiseError(e) => F.raiseError(e)
      case Delay(thunk) => F.delay(thunk())
      case _ =>
        F.suspend {
          IORunLoop.step(ioa) match {
            case Pure(a) => F.pure(a)
            case RaiseError(e) => F.raiseError(e)
            case async =>
              F.cancelable(cb => IO.Delay(async.unsafeRunCancelable(cb)))
          }
        }
    }

  /**
   * [[Concurrent]] instance built for `cats.data.EitherT` values initialized
   * with any `F` data type that also implements `Concurrent`.
   */
  implicit def catsEitherTConcurrent[F[_]: Concurrent, L]: Concurrent[EitherT[F, L, ?]] =
    new EitherTConcurrent[F, L] { def F = Concurrent[F] }

  /**
   * [[Concurrent]] instance built for `cats.data.OptionT` values initialized
   * with any `F` data type that also implements `Concurrent`.
   */
  implicit def catsOptionTConcurrent[F[_]: Concurrent]: Concurrent[OptionT[F, ?]] =
    new OptionTConcurrent[F] { def F = Concurrent[F] }

  /**
   * [[Concurrent]] instance built for `cats.data.StateT` values initialized
   * with any `F` data type that also implements `Concurrent`.
   */
  implicit def catsStateTAsync[F[_]: Concurrent, S]: Concurrent[StateT[F, S, ?]] =
    new StateTConcurrent[F, S] { def F = Concurrent[F] }

  /**
   * [[Concurrent]] instance built for `cats.data.WriterT` values initialized
   * with any `F` data type that also implements `Concurrent`.
   */
  implicit def catsWriterTAsync[F[_]: Concurrent, L: Monoid]: Concurrent[WriterT[F, L, ?]] =
    new WriterTConcurrent[F, L] { def F = Concurrent[F]; def L = Monoid[L] }

  private[effect] trait EitherTConcurrent[F[_], L] extends Async.EitherTAsync[F, L]
    with Concurrent[EitherT[F, L, ?]] {

    override protected implicit def F: Concurrent[F]
    override protected def FF = F

    def cancelable[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): EitherT[F, L, A] =
      EitherT.liftF(F.cancelable(k))(F)

    def start[A](fa: EitherT[F, L, A]) =
      EitherT.liftF(
        F.start(fa.value).map { fiber =>
          Fiber(
            EitherT(fiber.join),
            EitherT.liftF(fiber.cancel))
        })
  }

  private[effect] trait OptionTConcurrent[F[_]] extends Async.OptionTAsync[F]
    with Concurrent[OptionT[F, ?]] {

    override protected implicit def F: Concurrent[F]
    override protected def FF = F

    def cancelable[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): OptionT[F, A] =
      OptionT.liftF(F.cancelable(k))(F)

    def start[A](fa: OptionT[F, A]) = {
      OptionT.liftF(
        F.start(fa.value).map { fiber =>
          Fiber(OptionT(fiber.join), OptionT.liftF(fiber.cancel))
        })
    }
  }

  private[effect] trait StateTConcurrent[F[_], S] extends Async.StateTAsync[F, S]
    with Concurrent[StateT[F, S, ?]] {

    override protected implicit def F: Concurrent[F]
    override protected def FA = F

    def cancelable[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): StateT[F, S, A] =
      StateT.liftF(F.cancelable(k))(F)

    override def start[A](fa: StateT[F, S, A]) =
      StateT(s => F.start(fa.run(s)).map { fiber =>
        (s, Fiber(
          StateT(_ => fiber.join),
          StateT.liftF(fiber.cancel)))
      })
  }

  private[effect] trait WriterTConcurrent[F[_], L] extends Async.WriterTAsync[F, L]
    with Concurrent[WriterT[F, L, ?]] {

    override protected implicit def F: Concurrent[F]
    override protected def FA = F

    def cancelable[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): WriterT[F, L, A] =
      WriterT.liftF(F.cancelable(k))(L, F)

    def start[A](fa: WriterT[F, L, A]) =
      WriterT(F.start(fa.run).map { fiber =>
        (L.empty, Fiber(
          WriterT(fiber.join),
          WriterT.liftF(fiber.cancel)))
      })
  }
}
