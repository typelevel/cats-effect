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

import cats.data._
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.effect.ExitCase.Canceled
import cats.effect.IO.{Delay, Pure, RaiseError}
import cats.effect.internals.Callback.{rightUnit, successUnit}
import cats.effect.internals.{CancelableF, IORunLoop}
import cats.effect.internals.TrampolineEC.immediate
import cats.effect.implicits._
import cats.syntax.all._

import scala.annotation.implicitNotFound
import scala.concurrent.{Promise, TimeoutException}
import scala.concurrent.duration.FiniteDuration
import scala.util.Either
import simulacrum.typeclass

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
 * processes, suspended in the context of `F[_]` and that can be
 * canceled or joined.
 *
 * Without cancellation being baked in, we couldn't afford to do it.
 * See below.
 *
 * ==Cancelable builder==
 *
 * The signature exposed by the [[Concurrent!.cancelable cancelable]]
 * builder is this:
 *
 * {{{
 *   (Either[Throwable, A] => Unit) => CancelToken[F]
 * }}}
 *
 * [[CancelToken CancelToken[F]]] is just an alias for `F[Unit]` and
 * used to represent a cancellation action which will send a signal
 * to the producer, that may observe it and cancel the asynchronous
 * process.
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
 * can be canceled would be:
 *
 * {{{
 *   (A => Unit) => CancelToken
 * }}}
 *
 * This is approximately the signature of JavaScript's `setTimeout`,
 * which will return a "task ID" that can be used to cancel it. Or of
 * Java's `ScheduledExecutorService#schedule`, which will return a
 * Java `ScheduledFuture` that has a `.cancel()` operation on it.
 *
 * Similarly, for `Concurrent` data types, we can provide
 * cancellation logic that can be triggered in race conditions to
 * cancel the on-going processing, only that `Concurrent`'s
 * cancelation token is an action suspended in an `F[Unit]`.
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
 * canceled should the need arise, in order to trigger an early
 * release of allocated resources.
 *
 * Thus a [[Concurrent]] data type can safely participate in race
 * conditions, whereas a data type that is only [[Async]] cannot do it
 * without exposing and forcing the user to work with cancellation
 * tokens. An [[Async]] data type cannot expose for example a `start`
 * operation that is safe.
 *
 * == Resource-safety ==
 *
 * [[Concurrent]] data types are required to cooperate with [[Bracket]].
 * `Concurrent` being cancelable by law, what this means for the
 * corresponding `Bracket` is that cancelation can be observed and
 * that in the case of [[Bracket.bracketCase bracketCase]] the
 * [[ExitCase.Canceled]] branch will get executed on cancelation.
 *
 * By default the `cancelable` builder is derived from `bracketCase`
 * and from [[Async.asyncF asyncF]], so what this means is that
 * whatever you can express with `cancelable`, you can also express
 * with `bracketCase`.
 *
 * For [[Bracket.uncancelable uncancelable]], the [[Fiber.cancel cancel]]
 * signal has no effect on the result of [[Fiber.join join]] and
 * the cancelable token returned by [[ConcurrentEffect.runCancelable]]
 * on evaluation will have no effect if evaluated.
 *
 * So `uncancelable` must undo the cancellation mechanism of
 * [[Concurrent!.cancelable cancelable]], with this equivalence:
 *
 * {{{
 *   F.uncancelable(F.cancelable { cb => f(cb); token }) <-> F.async(f)
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
 *   // canceling the Fiber after start:
 *   for {
 *     fiber <- F.start(tick)
 *     _ <- fiber.cancel
 *     _ <- fiber.join
 *     _ <- F.delay { println("Tick!") }
 *   } yield ()
 * }}}
 *
 * When doing [[Bracket.bracket bracket]] or [[Bracket.bracketCase bracketCase]],
 * `acquire` and `release` operations are guaranteed to be uncancelable as well.
 */
@typeclass
@implicitNotFound("""Cannot find implicit value for Concurrent[${F}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Scheduler, a ContextShift[${F}]
or some equivalent type.""")
trait Concurrent[F[_]] extends Async[F] {

  /**
   * Start concurrent execution of the source suspended in
   * the `F` context.
   *
   * Returns a [[Fiber]] that can be used to either join or cancel
   * the running computation, being similar in spirit (but not
   * in implementation) to starting a thread.
   *
   * @see [[background]] for a safer alternative.
   */
  def start[A](fa: F[A]): F[Fiber[F, A]]

  /**
   * Returns a resource that will start execution of the effect in the background.
   *
   * In case the resource is closed while the effect is still running (e.g. due to a failure in `use`),
   * the background action will be canceled.
   *
   * A basic example with IO:
   *
   * {{{
   *   val longProcess = (IO.sleep(5.seconds) *> IO(println("Ping!"))).foreverM
   *
   *   val srv: Resource[IO, ServerBinding[IO]] = for {
   *     _ <- longProcess.background
   *     server <- server.run
   *   } yield server
   *
   *   val application = srv.use(binding => IO(println("Bound to " + binding)) *> IO.never)
   * }}}
   *
   * Here, we are starting a background process as part of the application's startup.
   * Afterwards, we initialize a server. Then, we use that server forever using `IO.never`.
   * This will ensure we never close the server resource unless somebody cancels the whole `application` action.
   *
   * If at some point of using the resource you want to wait for the result of the background action,
   * you can do so by sequencing the value inside the resource (it's equivalent to `join` on `Fiber`).
   *
   * This will start the background process, run another action, and wait for the result of the background process:
   *
   * {{{
   *   longProcess.background.use(await => anotherProcess *> await)
   * }}}
   *
   * In case the result of such an action is canceled, both processes will receive cancelation signals.
   * The same result can be achieved by using `anotherProcess &> longProcess` with the Parallel type class syntax.
   */
  def background[A](fa: F[A]): Resource[F, F[A]] =
    Resource.make(start(fa))(_.cancel)(this).map(_.join)(this)

  /**
   * Run two tasks concurrently, creating a race between them and returns a
   * pair containing both the winner's successful value and the loser
   * represented as a still-unfinished fiber.
   *
   * If the first task completes in error, then the result will
   * complete in error, the other task being canceled.
   *
   * On usage the user has the option of canceling the losing task,
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
  def racePair[A, B](fa: F[A], fb: F[B]): F[Either[(A, Fiber[F, B]), (Fiber[F, A], B)]]

  /**
   * Run two tasks concurrently and return the first to finish,
   * either in success or error. The loser of the race is canceled.
   *
   * The two tasks are potentially executed in parallel, the winner
   * being the first that signals a result.
   *
   * As an example see [[Concurrent.timeoutTo]]
   *
   * Also see [[racePair]] for a version that does not cancel
   * the loser automatically on successful results.
   */
  def race[A, B](fa: F[A], fb: F[B]): F[Either[A, B]] =
    flatMap(racePair(fa, fb)) {
      case Left((a, fiberB))  => map(fiberB.cancel)(_ => Left(a))
      case Right((fiberA, b)) => map(fiberA.cancel)(_ => Right(b))
    }

  /**
   * Creates a cancelable `F[A]` instance that executes an
   * asynchronous process on evaluation.
   *
   * This builder accepts a registration function that is
   * being injected with a side-effectful callback, to be called
   * when the asynchronous process is complete with a final result.
   *
   * The registration function is also supposed to return
   * a [[CancelToken]], which is nothing more than an
   * alias for `F[Unit]`, capturing the logic necessary for
   * canceling the asynchronous process for as long as it
   * is still active.
   *
   * Example:
   *
   * {{{
   *   import java.util.concurrent.ScheduledExecutorService
   *   import scala.concurrent.duration._
   *
   *   def sleep[F[_]](d: FiniteDuration)
   *     (implicit F: Concurrent[F], ec: ScheduledExecutorService): F[Unit] = {
   *
   *     F.cancelable { cb =>
   *       // Schedules task to run after delay
   *       val run = new Runnable { def run() = cb(Right(())) }
   *       val future = ec.schedule(run, d.length, d.unit)
   *
   *       // Cancellation logic, suspended in F
   *       F.delay(future.cancel(true))
   *     }
   *   }
   * }}}
   */
  def cancelable[A](k: (Either[Throwable, A] => Unit) => CancelToken[F]): F[A] =
    Concurrent.defaultCancelable(k)(this)

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

  /**
   * If no interruption happens during the execution of this method,
   * it behaves like `.attempt.flatMap`.
   *
   * Unlike `.attempt.flatMap` however, in the presence of
   * interruption this method offers the _continual guarantee_:
   * `fa` is interruptible, but if it completes execution, the
   * effects of `f` are guaranteed to execute.
   * This does not hold for `attempt.flatMap` since interruption can
   * happen in between `flatMap` steps.
   *
   * The typical use case for this function arises in the
   * implementation of concurrent abstractions, where you have
   * asynchronous operations waiting on some condition (which have to
   * be interruptible), mixed with operations that modify some shared
   * state if the condition holds true (which need to be guaranteed
   * to happen or the state will be inconsistent).
   *
   * Note that for the use case above:
   * - We cannot use:
   * {{{
   * waitingOp.bracket(..., modifyOp)
   * }}}
   * because it makes `waitingOp` uninterruptible.
   *
   * - We cannot use
   * {{{
   *  waitingOp.guaranteeCase {
   *    case Success => modifyOp(???)
   *    ...
   * }}}
   * if we need to use the result of `waitingOp`.
   *
   * - We cannot use
   * {{{
   *  waitingOp.attempt.flatMap(modifyOp)
   * }}}
   *  because it could be interrupted after `waitingOp` is done, but
   *  before `modifyOp` executes.
   *
   * To access this implementation as a standalone function, you can
   * use [[Concurrent$.continual Concurrent.continual]] in the
   * companion object.
   */
  def continual[A, B](fa: F[A])(f: Either[Throwable, A] => F[B]): F[B] =
    Concurrent.continual(fa)(f)(this)
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
      case Pure(a)       => F.pure(a)
      case RaiseError(e) => F.raiseError(e)
      case Delay(thunk)  => F.delay(thunk())
      case _ =>
        F.suspend {
          IORunLoop.step(ioa) match {
            case Pure(a)       => F.pure(a)
            case RaiseError(e) => F.raiseError(e)
            case async =>
              F.cancelable(cb => liftIO(async.unsafeRunCancelable(cb))(F))
          }
        }
    }

  /**
   * Returns an effect that either completes with the result of the source within
   * the specified time `duration` or otherwise evaluates the `fallback`.
   *
   * The source is cancelled in the event that it takes longer than
   * the `FiniteDuration` to complete, the evaluation of the fallback
   * happening immediately after that.
   *
   * @param duration is the time span for which we wait for the source to
   *        complete; in the event that the specified time has passed without
   *        the source completing, the `fallback` gets evaluated
   *
   * @param fallback is the task evaluated after the duration has passed and
   *        the source canceled
   */
  def timeoutTo[F[_], A](fa: F[A], duration: FiniteDuration, fallback: F[A])(implicit F: Concurrent[F],
                                                                             timer: Timer[F]): F[A] =
    F.race(fa, timer.sleep(duration)).flatMap {
      case Left(a)  => F.pure(a)
      case Right(_) => fallback
    }

  /**
   * Lazily memoizes `f`. Assuming no cancellation happens, the effect
   * `f` will be performed at most once for every time the returned
   * `F[F[A]]` is bound (when the inner `F[A]` is bound the first
   * time).
   *
   * If you try to cancel an inner `F[A]`, `f` is only interrupted if
   * there are no other active subscribers, whereas if there are, `f`
   * keeps running in the background.
   *
   * If `f` is successfully canceled, the next time an inner `F[A]`
   * is bound `f` will be restarted again. Note that this can mean
   * the effects of `f` happen more than once.
   *
   * You can look at `Async.memoize` for a version of this function
   * which does not allow cancellation.
   */
  def memoize[F[_], A](f: F[A])(implicit F: Concurrent[F]): F[F[A]] = {
    sealed trait State
    case class Subs(n: Int) extends State
    case object Done extends State

    case class Fetch(state: State, v: Deferred[F, Either[Throwable, A]], stop: Deferred[F, F[Unit]])

    Ref[F].of(Option.empty[Fetch]).map { state =>
      Deferred[F, Either[Throwable, A]].product(Deferred[F, F[Unit]]).flatMap {
        case (v, stop) =>
          def endState(ec: ExitCase[Throwable]) =
            state.modify {
              case None =>
                throw new AssertionError("unreachable")
              case s @ Some(Fetch(Done, _, _)) =>
                s -> F.unit
              case Some(Fetch(Subs(n), v, stop)) =>
                if (ec == ExitCase.Canceled && n == 1)
                  None -> stop.get.flatten
                else if (ec == ExitCase.Canceled)
                  Fetch(Subs(n - 1), v, stop).some -> F.unit
                else
                  Fetch(Done, v, stop).some -> F.unit
            }.flatten

          def fetch =
            f.attempt
              .flatMap(v.complete)
              .start
              .flatMap(fiber => stop.complete(fiber.cancel))

          state
            .modify {
              case s @ Some(Fetch(Done, v, _)) =>
                s -> v.get
              case Some(Fetch(Subs(n), v, stop)) =>
                Fetch(Subs(n + 1), v, stop).some -> v.get.guaranteeCase(endState)
              case None =>
                Fetch(Subs(1), v, stop).some -> fetch.bracketCase(_ => v.get) { case (_, ec) => endState(ec) }
            }
            .flatten
            .rethrow
      }
    }
  }

  /**
   * Returns an effect that either completes with the result of the source within
   * the specified time `duration` or otherwise raises a `TimeoutException`.
   *
   * The source is cancelled in the event that it takes longer than
   * the specified time duration to complete.
   *
   * @param duration is the time span for which we wait for the source to
   *        complete; in the event that the specified time has passed without
   *        the source completing, a `TimeoutException` is raised
   */
  def timeout[F[_], A](fa: F[A], duration: FiniteDuration)(implicit F: Concurrent[F], timer: Timer[F]): F[A] = {
    val timeoutException = F.suspend(F.raiseError[A](new TimeoutException(duration.toString)))
    timeoutTo(fa, duration, timeoutException)
  }

  /**
   * Function that creates an async and cancelable `F[A]`, similar with
   * [[Concurrent.cancelable]], but with the semantics of [[Async.asyncF]].
   *
   * Example building an asynchronous queue, with the state being
   * kept in [[cats.effect.concurrent.Ref]] and thus needing `cancelableF`:
   *
   * {{{
   *   import cats.implicits._
   *   import cats.effect.{CancelToken, Concurrent}
   *   import cats.effect.concurrent.Ref
   *   import scala.collection.immutable.Queue
   *
   *   final class AsyncQueue[F[_], A] private (
   *     ref: Ref[F, AsyncQueue.State[A]])
   *     (implicit F: Concurrent[F]) {
   *
   *     import AsyncQueue._
   *
   *     def poll: F[A] =
   *       Concurrent.cancelableF { cb =>
   *         ref.modify {
   *           case Await(listeners) =>
   *             (Await(listeners.enqueue(cb)), F.pure(unregister(cb)))
   *           case Available(queue) =>
   *             queue.dequeueOption match {
   *               case None =>
   *                 (Await(Queue(cb)), F.pure(unregister(cb)))
   *               case Some((a, queue2)) =>
   *                 (Available(queue2), F.delay(cb(Right(a))).as(unregister(cb)))
   *             }
   *         }.flatten
   *       }
   *
   *     def offer(a: A): F[Unit] = {
   *       // Left as an exercise for the reader ;-)
   *       ???
   *     }
   *
   *     private def unregister(cb: Either[Throwable, A] => Unit): CancelToken[F] =
   *       ref.update {
   *         case Await(listeners) => Await(listeners.filter(_ != cb))
   *         case other => other
   *       }
   *   }
   *
   *   object AsyncQueue {
   *     def empty[F[_], A](implicit F: Concurrent[F]): F[AsyncQueue[F, A]] =
   *       for {
   *         ref <- Ref.of[F, State[A]](Available(Queue.empty))
   *       } yield {
   *         new AsyncQueue[F, A](ref)
   *       }
   *
   *     private sealed trait State[A]
   *
   *     private case class Await[A](listeners: Queue[Either[Throwable, A] => Unit])
   *       extends State[A]
   *
   *     private case class Available[A](values: Queue[A])
   *       extends State[A]
   *   }
   * }}}
   *
   * ==Contract==
   *
   * The given generator function will be executed uninterruptedly, via `bracket`,
   * because due to the possibility of auto-cancellation we can have a resource
   * leak otherwise.
   *
   * This means that the task generated by `k` cannot be cancelled while being
   * evaluated. This is in contrast with [[Async.asyncF]], which does allow
   * cancelable tasks.
   *
   * @param k is a function that's going to be injected with a callback, to call on
   *        completion, returning an effect that's going to be evaluated to a
   *        cancellation token
   */
  def cancelableF[F[_], A](k: (Either[Throwable, A] => Unit) => F[CancelToken[F]])(implicit F: Concurrent[F]): F[A] =
    CancelableF(k)

  /**
   * This is the default [[Concurrent.continual]] implementation.
   */
  def continual[F[_], A, B](fa: F[A])(f: Either[Throwable, A] => F[B])(implicit F: Concurrent[F]): F[B] = {
    import cats.effect.implicits._

    Deferred.uncancelable[F, Either[Throwable, B]].flatMap { r =>
      fa.start.bracketCase { fiber =>
        fiber.join.guaranteeCase {
          case ExitCase.Completed | ExitCase.Error(_) => fiber.join.attempt.flatMap(f).attempt.flatMap(r.complete)
          case _                                      => F.unit //will be canceled in release of enclosing bracketCase
        }
      } {
        case (fiber, ExitCase.Canceled) =>
          // This cancel has to be here, and not in the `guaranteeCase` above, as the `use` of `bracketCase` might not be evaluated.
          // See https://github.com/typelevel/cats-effect/issues/793
          fiber.cancel
        case _ => F.unit
      }.attempt *> r.get.rethrow
    }
  }

  /**
   * Like `Parallel.parTraverse`, but limits the degree of parallelism.
   */
  def parTraverseN[T[_]: Traverse, M[_], A, B](n: Long)(ta: T[A])(f: A => M[B])(implicit M: Concurrent[M],
                                                                                P: Parallel[M]): M[T[B]] =
    for {
      semaphore <- Semaphore(n)(M)
      tb <- ta.parTraverse { a =>
        semaphore.withPermit(f(a))
      }
    } yield tb

  /**
   * Like `Parallel.parSequence`, but limits the degree of parallelism.
   */
  def parSequenceN[T[_]: Traverse, M[_], A](n: Long)(tma: T[M[A]])(implicit M: Concurrent[M], P: Parallel[M]): M[T[A]] =
    for {
      semaphore <- Semaphore(n)(M)
      mta <- tma.map(semaphore.withPermit).parSequence
    } yield mta

  /**
   * [[Concurrent]] instance built for `cats.data.EitherT` values initialized
   * with any `F` data type that also implements `Concurrent`.
   */
  implicit def catsEitherTConcurrent[F[_]: Concurrent, L]: Concurrent[EitherT[F, L, *]] =
    new EitherTConcurrent[F, L] { def F = Concurrent[F] }

  /**
   * [[Concurrent]] instance built for `cats.data.OptionT` values initialized
   * with any `F` data type that also implements `Concurrent`.
   */
  implicit def catsOptionTConcurrent[F[_]: Concurrent]: Concurrent[OptionT[F, *]] =
    new OptionTConcurrent[F] { def F = Concurrent[F] }

  /**
   * [[Concurrent]] instance built for `cats.data.Kleisli` values initialized
   * with any `F` data type that also implements `Concurrent`.
   */
  implicit def catsKleisliConcurrent[F[_]: Concurrent, R]: Concurrent[Kleisli[F, R, *]] =
    new KleisliConcurrent[F, R] { def F = Concurrent[F] }

  /**
   * [[Concurrent]] instance built for `cats.data.WriterT` values initialized
   * with any `F` data type that also implements `Concurrent`.
   */
  implicit def catsWriterTConcurrent[F[_]: Concurrent, L: Monoid]: Concurrent[WriterT[F, L, *]] =
    new WriterTConcurrent[F, L] { def F = Concurrent[F]; def L = Monoid[L] }

  /**
   * [[Concurrent]] instance built for `cats.data.IorT` values initialized
   * with any `F` data type that also implements `Concurrent`.
   */
  implicit def catsIorTConcurrent[F[_]: Concurrent, L: Semigroup]: Concurrent[IorT[F, L, *]] =
    new IorTConcurrent[F, L] { def F = Concurrent[F]; def L = Semigroup[L] }

  private[effect] trait EitherTConcurrent[F[_], L] extends Async.EitherTAsync[F, L] with Concurrent[EitherT[F, L, *]] {
    implicit override protected def F: Concurrent[F]
    override protected def FF = F

    // Needed to drive static checks, otherwise the
    // compiler will choke on type inference :-(
    type Fiber[A] = cats.effect.Fiber[EitherT[F, L, *], A]

    override def cancelable[A](k: (Either[Throwable, A] => Unit) => CancelToken[EitherT[F, L, *]]): EitherT[F, L, A] =
      EitherT.liftF(F.cancelable(k.andThen(_.value.map(_ => ()))))(F)

    override def start[A](fa: EitherT[F, L, A]) =
      EitherT.liftF(F.start(fa.value).map(fiberT))

    override def racePair[A, B](fa: EitherT[F, L, A],
                                fb: EitherT[F, L, B]): EitherT[F, L, Either[(A, Fiber[B]), (Fiber[A], B)]] =
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

  private[effect] trait OptionTConcurrent[F[_]] extends Async.OptionTAsync[F] with Concurrent[OptionT[F, *]] {
    implicit override protected def F: Concurrent[F]
    override protected def FF = F

    // Needed to drive static checks, otherwise the
    // compiler will choke on type inference :-(
    type Fiber[A] = cats.effect.Fiber[OptionT[F, *], A]

    override def cancelable[A](k: (Either[Throwable, A] => Unit) => CancelToken[OptionT[F, *]]): OptionT[F, A] =
      OptionT.liftF(F.cancelable(k.andThen(_.value.map(_ => ()))))(F)

    override def start[A](fa: OptionT[F, A]) =
      OptionT.liftF(F.start(fa.value).map(fiberT))

    override def racePair[A, B](fa: OptionT[F, A],
                                fb: OptionT[F, B]): OptionT[F, Either[(A, Fiber[B]), (Fiber[A], B)]] =
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

    protected def fiberT[A](fiber: effect.Fiber[F, Option[A]]): Fiber[A] =
      Fiber(OptionT(fiber.join), OptionT.liftF(fiber.cancel))
  }

  private[effect] trait WriterTConcurrent[F[_], L] extends Async.WriterTAsync[F, L] with Concurrent[WriterT[F, L, *]] {
    implicit override protected def F: Concurrent[F]
    override protected def FA = F

    // Needed to drive static checks, otherwise the
    // compiler will choke on type inference :-(
    type Fiber[A] = cats.effect.Fiber[WriterT[F, L, *], A]

    override def cancelable[A](k: (Either[Throwable, A] => Unit) => CancelToken[WriterT[F, L, *]]): WriterT[F, L, A] =
      WriterT.liftF(F.cancelable(k.andThen(_.run.map(_ => ()))))(L, F)

    override def start[A](fa: WriterT[F, L, A]) =
      WriterT(F.start(fa.run).map { fiber =>
        (L.empty, fiberT[A](fiber))
      })

    override def racePair[A, B](fa: WriterT[F, L, A],
                                fb: WriterT[F, L, B]): WriterT[F, L, Either[(A, Fiber[B]), (Fiber[A], B)]] =
      WriterT(F.racePair(fa.run, fb.run).map {
        case Left(((l, value), fiber)) =>
          (l, Left((value, fiberT(fiber))))
        case Right((fiber, (l, value))) =>
          (l, Right((fiberT(fiber), value)))
      })

    protected def fiberT[A](fiber: effect.Fiber[F, (L, A)]): Fiber[A] =
      Fiber(WriterT(fiber.join), WriterT.liftF(fiber.cancel))
  }

  abstract private[effect] class KleisliConcurrent[F[_], R]
      extends Async.KleisliAsync[F, R]
      with Concurrent[Kleisli[F, R, *]] {
    implicit override protected def F: Concurrent[F]
    // Needed to drive static checks, otherwise the
    // compiler can choke on type inference :-(
    type Fiber[A] = cats.effect.Fiber[Kleisli[F, R, *], A]

    override def cancelable[A](k: (Either[Throwable, A] => Unit) => CancelToken[Kleisli[F, R, *]]): Kleisli[F, R, A] =
      Kleisli(r => F.cancelable(k.andThen(_.run(r).map(_ => ()))))

    override def start[A](fa: Kleisli[F, R, A]): Kleisli[F, R, Fiber[A]] =
      Kleisli(r => F.start(fa.run(r)).map(fiberT))

    override def racePair[A, B](fa: Kleisli[F, R, A], fb: Kleisli[F, R, B]) =
      Kleisli { r =>
        F.racePair(fa.run(r), fb.run(r)).map {
          case Left((a, fiber))  => Left((a, fiberT[B](fiber)))
          case Right((fiber, b)) => Right((fiberT[A](fiber), b))
        }
      }

    protected def fiberT[A](fiber: effect.Fiber[F, A]): Fiber[A] =
      Fiber(Kleisli.liftF(fiber.join), Kleisli.liftF(fiber.cancel))
  }

  private[effect] trait IorTConcurrent[F[_], L] extends Async.IorTAsync[F, L] with Concurrent[IorT[F, L, *]] {
    implicit override protected def F: Concurrent[F]
    override protected def FA = F

    // Needed to drive static checks, otherwise the
    // compiler will choke on type inference :-(
    type Fiber[A] = cats.effect.Fiber[IorT[F, L, *], A]

    override def cancelable[A](k: (Either[Throwable, A] => Unit) => CancelToken[IorT[F, L, *]]): IorT[F, L, A] =
      IorT.liftF(F.cancelable(k.andThen(_.value.map(_ => ()))))(F)

    override def start[A](fa: IorT[F, L, A]) =
      IorT.liftF(F.start(fa.value).map(fiberT))

    override def racePair[A, B](fa: IorT[F, L, A],
                                fb: IorT[F, L, B]): IorT[F, L, Either[(A, Fiber[B]), (Fiber[A], B)]] =
      IorT(F.racePair(fa.value, fb.value).flatMap {
        case Left((value, fiberB)) =>
          value match {
            case l @ Ior.Left(_) =>
              fiberB.cancel.map(_ => l)
            case Ior.Right(r) =>
              F.pure(Ior.Right(Left((r, fiberT[B](fiberB)))))
            case Ior.Both(l, r) =>
              F.pure(Ior.Both(l, Left((r, fiberT[B](fiberB)))))
          }
        case Right((fiberA, value)) =>
          value match {
            case l @ Ior.Left(_) =>
              fiberA.cancel.map(_ => l)
            case Ior.Right(r) =>
              F.pure(Ior.Right(Right((fiberT[A](fiberA), r))))
            case Ior.Both(l, r) =>
              F.pure(Ior.Both(l, Right((fiberT[A](fiberA), r))))
          }
      })

    protected def fiberT[A](fiber: effect.Fiber[F, Ior[L, A]]): Fiber[A] =
      Fiber(IorT(fiber.join), IorT.liftF(fiber.cancel))
  }

  /**
   * Internal API — Cancelable builder derived from
   * [[Async.asyncF]] and [[Bracket.bracketCase]].
   */
  private def defaultCancelable[F[_], A](
    k: (Either[Throwable, A] => Unit) => CancelToken[F]
  )(implicit F: Async[F]): F[A] =
    F.asyncF[A] { cb =>
      // For back-pressuring bracketCase until the callback gets called.
      // Need to work with `Promise` due to the callback being side-effecting.
      val latch = Promise[Unit]()
      val latchF = F.async[Unit](cb => latch.future.onComplete(_ => cb(rightUnit))(immediate))
      // Side-effecting call; unfreezes latch in order to allow bracket to finish
      val token = k { result =>
        latch.complete(successUnit)
        cb(result)
      }
      F.bracketCase(F.pure(token))(_ => latchF) {
        case (cancel, Canceled) => cancel
        case _                  => F.unit
      }
    }
}
