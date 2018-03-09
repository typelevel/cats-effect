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
 *  1. can provide logic for cancelation, to be used in race
 *     conditions in order to release resources early
 *     (in its [[Concurrent!.cancelable cancelable]] builder)
 *
 * Due to these restrictions, this type class also affords to describe
 * a [[Concurrent!.start start]] operation that can start async
 * processing, suspended in the context of `F[_]` and that can be
 * cancelled or joined.
 *
 * Without cancelation being baked in, we couldn't afford to do it.
 * See below.
 *
 * ==Cancelable builder==
 *
 * The signature exposed by the [[Concurrent!.cancelable cancelable]]
 * builder is this:
 *
 * {{{
 *   (Either[Throwable, A] => Unit) => F[Unit]
 * }}}
 *
 * `F[Unit]` is used to represent a cancelation action which will
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
 * cancelation logic, that can be triggered in race conditions to
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
 * cancelation token that can be used to trigger a forceful
 * interruption of the timer. This is not a normal "dispose" or
 * "finally" clause in a try/catch block, because "cancel" in the
 * context of an asynchronous process is ''concurrent'' with the
 * task's own run-loop.
 *
 * To understand what this means, consider that in the case of our
 * `sleep` as described above, on cancelation we'd need a way to
 * signal to the underlying `ScheduledExecutorService` to forcefully
 * remove the scheduled `Runnable` from its internal queue of
 * scheduled tasks, ''before'' its execution. Therefore, without a
 * cancelable data type, a safe signature needs to return a
 * cancelation token, so it would look like this:
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
 * without exposing and forcing the user to work with cancelation
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
   * Returns a new `F` that mirrors the source, but that is uninterruptible.
   *
   * This means that the [[Fiber.cancel cancel]] signal has no effect on the
   * result of [[Fiber.join join]] and that the cancelable token returned by
   * [[ConcurrentEffect.runCancelable]] on evaluation will have no effect.
   *
   * This operation is undoing the cancelation mechanism of [[cancelable]],
   * with this equivalence:
   *
   * {{{
   *   F.uncancelable(F.cancelable { cb => f(cb); io }) <-> F.async(f)
   * }}}
   *
   * Sample:
   *
   * {{{
   *   val F = Concurrent[IO]
   *   val timer = Timer[IO]
   *
   *   // Normally Timer#sleep yields cancelable tasks
   *   val tick = F.uncancelable(timer.sleep(10.seconds))
   *
   *   // This prints "Tick!" after 10 seconds, even if we are
   *   // cancelling the Fiber after start:
   *   for {
   *     fiber <- F.start(tick)
   *     _ <- fiber.cancel
   *     _ <- fiber.join
   *   } yield {
   *     println("Tick!")
   *   }
   * }}}
   *
   * Cancelable effects are great in race conditions, however sometimes
   * this operation is necessary to ensure that the bind continuation
   * of a task (the following `flatMap` operations) are also evaluated
   * no matter what.
   */
  def uncancelable[A](fa: F[A]): F[A]

  /**
   * Returns a new `F` value that mirrors the source for normal
   * termination, but that triggers the given error on cancelation.
   *
   * This `onCancelRaiseError` operator transforms any task into one
   * that on cancelation will terminate with the given error, thus
   * transforming potentially non-terminating tasks into ones that
   * yield a certain error.
   *
   * {{{
   *   import scala.concurrent.CancellationException
   *
   *   val F = Concurrent[IO]
   *   val timer = Timer[IO]
   *
   *   val error = new CancellationException("Boo!")
   *   val fa = F.onCancelRaiseError(timer.sleep(5.seconds, error))
   *
   *   fa.start.flatMap { fiber =>
   *     fiber.cancel *> fiber.join
   *   }
   * }}}
   *
   * Without "onCancelRaiseError" the [[Timer.sleep sleep]] operation
   * yields a non-terminating task on cancellation. But by applying
   * "onCancelRaiseError", the yielded task above will terminate with
   * the indicated "CancellationException" reference, which we can
   * then also distinguish from other errors thrown in the `F` context.
   *
   * Depending on the implementation, tasks that are canceled can
   * become non-terminating. This operation ensures that when
   * cancelation happens, the resulting task is terminated with an
   * error, such that logic can be scheduled to happen after
   * cancelation:
   *
   * {{{
   *   import scala.concurrent.CancellationException
   *   val wasCanceled = new CancellationException()
   *
   *   F.onCancelRaiseError(fa, wasCanceled).attempt.flatMap {
   *     case Right(a) =>
   *       F.delay(println(s"Success: \$a"))
   *     case Left(`wasCanceled`) =>
   *       F.delay(println("Was canceled!"))
   *     case Left(error) =>
   *       F.delay(println(s"Terminated in error: \$error"))
   *   }
   * }}}
   *
   * This technique is how a "bracket" operation can be implemented.
   *
   * Besides sending the cancelation signal, this operation also cuts
   * the connection between the producer and the consumer. Example:
   *
   * {{{
   *   val F = Concurrent[IO]
   *   val timer = Timer[IO]
   *
   *   // This task is uninterruptible ;-)
   *   val tick = F.uncancelable(
   *     for {
   *       _ <- timer.sleep(5.seconds)
   *       _ <- IO(println("Tick!"))
   *     } yield ())
   *
   *   // Builds an value that triggers an exception on cancellation
   *   val loud = F.onCancelRaiseError(tick, new CancellationException)
   * }}}
   *
   * In this example the `loud` reference will be completed with a
   * "CancellationException", as indicated via "onCancelRaiseError".
   * The logic of the source won't get cancelled, because we've
   * embedded it all in [[uncancelable]]. But its bind continuation is
   * not allowed to continue after that, its final result not being
   * allowed to be signaled.
   *
   * Therefore this also transforms [[uncancelable]] values into ones
   * that can be canceled. The logic of the source, its run-loop
   * might not be interruptible, however `cancel` on a value on which
   * `onCancelRaiseError` was applied will cut the connection from
   * the producer, the consumer receiving the indicated error instead.
   */
  def onCancelRaiseError[A](fa: F[A], e: Throwable): F[A]

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
   * Run two tasks concurrently, creating a race between them and returns a
   * pair containing both the winner's successful value and the loser
   * represented as a still-unfinished fiber.
   *
   * If the first task completes in error, then the result will
   * complete in error, the other task being cancelled.
   *
   * On usage the user has the option of cancelling the losing task,
   * this being equivalent with plain [[race]]:
   *
   * {{{
   *   val ioA: IO[A] = ???
   *   val ioB: IO[B] = ???
   *
   *   Concurrent[IO].racePair(ioA, ioB).flatMap {
   *     case Left((a, fiberB)) =>
   *       fiberB.cancel.map(_ => a)
   *     case Right((fiberA, b)) =>
   *       fiberA.cancel.map(_ => b)
   *   }
   * }}}
   *
   * See [[race]] for a simpler version that cancels the loser
   * immediately.
   */
  def racePair[A,B](fa: F[A], fb: F[B]): F[Either[(A, Fiber[F, B]), (Fiber[F, A], B)]]

  /**
   * Run two tasks concurrently and return the first to finish,
   * either in success or error. The loser of the race is cancelled.
   *
   * The two tasks are potentially executed in parallel, the winner
   * being the first that signals a result.
   *
   * As an example, this would be the implementation of a "timeout"
   * operation:
   *
   * {{{
   *   import cats.effect._
   *   import scala.concurrent.duration._
   *
   *   def timeoutTo[F[_], A](fa: F[A], after: FiniteDuration, fallback: F[A])
   *     (implicit F: Concurrent[F], timer: Timer[F]): F[A] = {
   *
   *      F.race(fa, timer.sleep(timer)).flatMap {
   *        case Left((a, _)) => F.pure(a)
   *        case Right((_, _)) => fallback
   *      }
   *   }
   *
   *   def timeout[F[_], A](fa: F[A], after: FiniteDuration)
   *     (implicit F: Concurrent[F], timer: Timer[F]): F[A] = {
   *
   *      timeoutTo(fa, after,
   *        F.raiseError(new TimeoutException(after.toString)))
   *   }
   * }}}
   *
   * Also see [[racePair]] for a version that does not cancel
   * the loser automatically on successful results.
   */
  def race[A, B](fa: F[A], fb: F[B]): F[Either[A, B]] =
    flatMap(racePair(fa, fb)) {
      case Left((a, fiberB)) => map(fiberB.cancel)(_ => Left(a))
      case Right((fiberA, b)) => map(fiberA.cancel)(_ => Right(b))
    }

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
  implicit def catsStateTConcurrent[F[_]: Concurrent, S]: Concurrent[StateT[F, S, ?]] =
    new StateTConcurrent[F, S] { def F = Concurrent[F] }

  /**
   * [[Concurrent]] instance built for `cats.data.WriterT` values initialized
   * with any `F` data type that also implements `Concurrent`.
   */
  implicit def catsWriterTConcurrent[F[_]: Concurrent, L: Monoid]: Concurrent[WriterT[F, L, ?]] =
    new WriterTConcurrent[F, L] { def F = Concurrent[F]; def L = Monoid[L] }

  private[effect] trait EitherTConcurrent[F[_], L] extends Async.EitherTAsync[F, L]
    with Concurrent[EitherT[F, L, ?]] {

    override protected implicit def F: Concurrent[F]
    override protected def FF = F

    // Needed to drive static checks, otherwise the
    // compiler will choke on type inference :-(
    type Fiber[A] = cats.effect.Fiber[EitherT[F, L, ?], A]

    def cancelable[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): EitherT[F, L, A] =
      EitherT.liftF(F.cancelable(k))(F)

    def uncancelable[A](fa: EitherT[F, L, A]): EitherT[F, L, A] =
      EitherT(F.uncancelable(fa.value))

    def onCancelRaiseError[A](fa: EitherT[F, L, A], e: Throwable): EitherT[F, L, A] =
      EitherT(F.onCancelRaiseError(fa.value, e))

    def start[A](fa: EitherT[F, L, A]) =
      EitherT.liftF(F.start(fa.value).map(fiberT))

    def racePair[A, B](fa: EitherT[F, L, A], fb: EitherT[F, L, B]): EitherT[F, L, Either[(A, Fiber[B]), (Fiber[A], B)]] =
      EitherT(F.racePair(fa.value, fb.value).flatMap {
        case Left((value, fiberB)) =>
          value match {
            case Left(_) =>
              fiberB.cancel.map(_ => value.asInstanceOf[Left[L, Nothing]])
            case Right(r) =>
              F.pure(Right(Left((r, fiberT[B](fiberB)))))
          }
        case Right((fiberA, value)) =>
          value match {
            case Left(_) =>
              fiberA.cancel.map(_ => value.asInstanceOf[Left[L, Nothing]])
            case Right(r) =>
              F.pure(Right(Right((fiberT[A](fiberA), r))))
          }
      })

    protected def fiberT[A](fiber: effect.Fiber[F, Either[L, A]]): Fiber[A] =
      Fiber(EitherT(fiber.join), EitherT.liftF(fiber.cancel))
  }

  private[effect] trait OptionTConcurrent[F[_]] extends Async.OptionTAsync[F]
    with Concurrent[OptionT[F, ?]] {

    override protected implicit def F: Concurrent[F]
    override protected def FF = F

    // Needed to drive static checks, otherwise the
    // compiler will choke on type inference :-(
    type Fiber[A] = cats.effect.Fiber[OptionT[F, ?], A]

    def cancelable[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): OptionT[F, A] =
      OptionT.liftF(F.cancelable(k))(F)

    def start[A](fa: OptionT[F, A]) =
      OptionT.liftF(F.start(fa.value).map(fiberT))

    def racePair[A, B](fa: OptionT[F, A], fb: OptionT[F, B]): OptionT[F, Either[(A, Fiber[B]), (Fiber[A], B)]] =
      OptionT(F.racePair(fa.value, fb.value).flatMap {
        case Left((value, fiberB)) =>
          value match {
            case None =>
              fiberB.cancel.map(_ => None)
            case Some(r) =>
              F.pure(Some(Left((r, fiberT[B](fiberB)))))
          }
        case Right((fiberA, value)) =>
          value match {
            case None =>
              fiberA.cancel.map(_ => None)
            case Some(r) =>
              F.pure(Some(Right((fiberT[A](fiberA), r))))
          }
      })
      
    def uncancelable[A](fa: OptionT[F, A]): OptionT[F, A] =
      OptionT(F.uncancelable(fa.value))

    def onCancelRaiseError[A](fa: OptionT[F, A], e: Throwable): OptionT[F, A] =
      OptionT(F.onCancelRaiseError(fa.value, e))

    protected def fiberT[A](fiber: effect.Fiber[F, Option[A]]): Fiber[A] =
      Fiber(OptionT(fiber.join), OptionT.liftF(fiber.cancel))
  }

  private[effect] trait StateTConcurrent[F[_], S] extends Async.StateTAsync[F, S]
    with Concurrent[StateT[F, S, ?]] {

    override protected implicit def F: Concurrent[F]
    override protected def FA = F

    // Needed to drive static checks, otherwise the
    // compiler will choke on type inference :-(
    type Fiber[A] = cats.effect.Fiber[StateT[F, S, ?], A]

    def cancelable[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): StateT[F, S, A] =
      StateT.liftF(F.cancelable(k))(F)

    def start[A](fa: StateT[F, S, A]): StateT[F, S, Fiber[A]] =
      StateT(s => F.start(fa.run(s)).map { fiber => (s, fiberT(fiber)) })

    def racePair[A, B](fa: StateT[F, S, A], fb: StateT[F, S, B]): StateT[F, S, Either[(A, Fiber[B]), (Fiber[A], B)]] =
      StateT { startS =>
        F.racePair(fa.run(startS), fb.run(startS)).map {
          case Left(((s, value), fiber)) =>
            (s, Left((value, fiberT(fiber))))
          case Right((fiber, (s, value))) =>
            (s, Right((fiberT(fiber), value)))
        }
      }

    def uncancelable[A](fa: StateT[F, S, A]): StateT[F, S, A] =
      fa.transformF(F.uncancelable)

    def onCancelRaiseError[A](fa: StateT[F, S, A], e: Throwable): StateT[F, S, A] =
      fa.transformF(F.onCancelRaiseError(_, e))

    protected def fiberT[A](fiber: effect.Fiber[F, (S, A)]): Fiber[A] =
      Fiber(StateT(_ => fiber.join), StateT.liftF(fiber.cancel))
  }

  private[effect] trait WriterTConcurrent[F[_], L] extends Async.WriterTAsync[F, L]
    with Concurrent[WriterT[F, L, ?]] {

    override protected implicit def F: Concurrent[F]
    override protected def FA = F

    // Needed to drive static checks, otherwise the
    // compiler will choke on type inference :-(
    type Fiber[A] = cats.effect.Fiber[WriterT[F, L, ?], A]

    def cancelable[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): WriterT[F, L, A] =
      WriterT.liftF(F.cancelable(k))(L, F)

    def uncancelable[A](fa: WriterT[F, L, A]): WriterT[F, L, A] =
      WriterT(F.uncancelable(fa.run))

    def onCancelRaiseError[A](fa: WriterT[F, L, A], e: Throwable): WriterT[F, L, A] =
      WriterT(F.onCancelRaiseError(fa.run, e))

    def start[A](fa: WriterT[F, L, A]) =
      WriterT(F.start(fa.run).map { fiber =>
        (L.empty, fiberT[A](fiber))
      })

    def racePair[A, B](fa: WriterT[F, L, A], fb: WriterT[F, L, B]): WriterT[F, L, Either[(A, Fiber[B]), (Fiber[A], B)]] =
      WriterT(F.racePair(fa.run, fb.run).map {
        case Left(((l, value), fiber)) =>
          (l, Left((value, fiberT(fiber))))
        case Right((fiber, (l, value))) =>
          (l, Right((fiberT(fiber), value)))
      })

    protected def fiberT[A](fiber: effect.Fiber[F, (L, A)]): Fiber[A] =
      Fiber(WriterT(fiber.join), WriterT.liftF(fiber.cancel))
  }
}
