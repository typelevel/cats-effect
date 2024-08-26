/*
 * Copyright 2020-2024 Typelevel
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

package cats.effect

import cats.{
  Align,
  Alternative,
  Applicative,
  CommutativeApplicative,
  Eval,
  Foldable,
  Functor,
  Id,
  Monad,
  Monoid,
  NonEmptyParallel,
  Now,
  Parallel,
  Semigroup,
  SemigroupK,
  Show,
  StackSafeMonad,
  Traverse
}
import cats.data.Ior
import cats.effect.instances.spawn
import cats.effect.kernel.CancelScope
import cats.effect.kernel.GenTemporal.handleDuration
import cats.effect.std.{Backpressure, Console, Env, Supervisor, UUIDGen}
import cats.effect.tracing.{Tracing, TracingEvent}
import cats.effect.unsafe.IORuntime
import cats.syntax._
import cats.syntax.all._

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent._
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

import java.util.UUID
import java.util.concurrent.Executor

import Platform.static

/**
 * A pure abstraction representing the intention to perform a side effect, where the result of
 * that side effect may be obtained synchronously (via return) or asynchronously (via callback).
 *
 * `IO` values are pure, immutable values and thus preserve referential transparency, being
 * usable in functional programming. An `IO` is a data structure that represents just a
 * description of a side effectful computation.
 *
 * `IO` can describe synchronous or asynchronous computations that:
 *
 *   1. on evaluation yield exactly one result 2. can end in either success or failure and in
 *      case of failure `flatMap` chains get short-circuited (`IO` implementing the algebra of
 *      `MonadError`) 3. can be canceled, but note this capability relies on the user to provide
 *      cancelation logic
 *
 * Effects described via this abstraction are not evaluated until the "end of the world", which
 * is to say, when one of the "unsafe" methods are used. Effectful results are not memoized,
 * meaning that memory overhead is minimal (and no leaks), and also that a single effect may be
 * run multiple times in a referentially-transparent manner. For example:
 *
 * {{{
 * val ioa = IO.println("hey!")
 *
 * val program = for {
 *   _ <- ioa
 *   _ <- ioa
 * } yield ()
 *
 * program.unsafeRunSync()
 * }}}
 *
 * The above will print "hey!" twice, as the effect will be re-run each time it is sequenced in
 * the monadic chain.
 *
 * `IO` is trampolined in its `flatMap` evaluation. This means that you can safely call
 * `flatMap` in a recursive function of arbitrary depth, without fear of blowing the stack.
 *
 * {{{
 * def fib(n: Int, a: Long = 0, b: Long = 1): IO[Long] =
 *   IO.pure(a + b) flatMap { b2 =>
 *     if (n > 0)
 *       fib(n - 1, b, b2)
 *     else
 *       IO.pure(a)
 *   }
 * }}}
 *
 * @see
 *   [[IOApp]] for the preferred way of executing whole programs wrapped in `IO`
 */
sealed abstract class IO[+A] private () extends IOPlatform[A] {

  private[effect] def tag: Byte

  /**
   * Like [[*>]], but keeps the result of the source.
   *
   * For a similar method that also runs the parameter in case of failure or interruption, see
   * [[guarantee]].
   */
  def <*[B](that: IO[B]): IO[A] =
    productL(that)

  /**
   * Runs the current IO, then runs the parameter, keeping its result. The result of the first
   * action is ignored. If the source fails, the other action won't run. Not suitable for use
   * when the parameter is a recursive reference to the current expression.
   *
   * @see
   *   [[>>]] for the recursion-safe, lazily evaluated alternative
   */
  def *>[B](that: IO[B]): IO[B] =
    productR(that)

  /**
   * Runs the current IO, then runs the parameter, keeping its result. The result of the first
   * action is ignored. If the source fails, the other action won't run. Evaluation of the
   * parameter is done lazily, making this suitable for recursion.
   *
   * @see
   *   [*>] for the strictly evaluated alternative
   */
  def >>[B](that: => IO[B]): IO[B] =
    flatMap(_ => that)

  def !>[B](that: IO[B]): IO[B] =
    forceR(that)

  /**
   * Runs this IO and the parameter in parallel.
   *
   * Failure in either of the IOs will cancel the other one. If the whole computation is
   * canceled, both actions are also canceled.
   */
  def &>[B](that: IO[B]): IO[B] =
    both(that).map { case (_, b) => b }

  /**
   * Like [[&>]], but keeps the result of the source
   */
  def <&[B](that: IO[B]): IO[A] =
    both(that).map { case (a, _) => a }

  /**
   * Transform certain errors using `pf` and rethrow them. Non matching errors and successful
   * values are not affected by this function.
   *
   * Implements `ApplicativeError.adaptError`.
   */
  def adaptError[E](pf: PartialFunction[Throwable, Throwable]): IO[A] =
    recoverWith(pf.andThen(IO.raiseError[A] _))

  /**
   * Replaces the result of this IO with the given value.
   */
  def as[B](b: B): IO[B] =
    map(_ => b)

  /**
   * Materializes any sequenced exceptions into value space, where they may be handled.
   *
   * This is analogous to the `catch` clause in `try`/`catch`, being the inverse of
   * `IO.raiseError`. Thus:
   *
   * {{{
   * IO.raiseError(ex).attempt.unsafeRunSync() === Left(ex)
   * }}}
   *
   * @see
   *   [[IO.raiseError]]
   * @see
   *   [[rethrow]]
   */
  def attempt: IO[Either[Throwable, A]] =
    IO.Attempt(this)

  /**
   * Reifies the value or error of the source and performs an effect on the result, then
   * recovers the original value or error back into `IO`.
   *
   * Implements `MonadError.attemptTap`.
   */
  def attemptTap[B](f: Either[Throwable, A] => IO[B]): IO[A] =
    attempt.flatTap(f).rethrow

  /**
   * Replaces failures in this IO with an empty Option.
   */
  def option: IO[Option[A]] =
    redeem(_ => None, Some(_))

  /**
   * Runs the current and given IO in parallel, producing the pair of the outcomes. Both
   * outcomes are produced, regardless of whether they complete successfully.
   *
   * @see
   *   [[both]] for the version which embeds the outcomes to produce a pair of the results
   * @see
   *   [[raceOutcome]] for the version which produces the outcome of the winner and cancels the
   *   loser of the race
   */
  def bothOutcome[B](that: IO[B]): IO[(OutcomeIO[A @uncheckedVariance], OutcomeIO[B])] =
    IO.asyncForIO.bothOutcome(this, that)

  /**
   * Runs the current and given IO in parallel, producing the pair of the results. If either
   * fails with an error, the result of the whole will be that error and the other will be
   * canceled.
   *
   * @see
   *   [[bothOutcome]] for the version which produces the outcome of both effects executed in
   *   parallel
   * @see
   *   [[race]] for the version which produces the result of the winner and cancels the loser of
   *   the race
   */
  def both[B](that: IO[B]): IO[(A, B)] =
    IO.both(this, that)

  /**
   * Returns an `IO` action that treats the source task as the acquisition of a resource, which
   * is then exploited by the `use` function and then `released`.
   *
   * The `bracket` operation is the equivalent of the `try {} catch {} finally {}` statements
   * from mainstream languages.
   *
   * The `bracket` operation installs the necessary exception handler to release the resource in
   * the event of an exception being raised during the computation, or in case of cancelation.
   *
   * If an exception is raised, then `bracket` will re-raise the exception ''after'' performing
   * the `release`. If the resulting task gets canceled, then `bracket` will still perform the
   * `release`, but the yielded task will be non-terminating (equivalent with [[IO.never]]).
   *
   * Example:
   *
   * {{{
   *   import java.io._
   *
   *   def readFile(file: File): IO[String] = {
   *     // Opening a file handle for reading text
   *     val acquire = IO(new BufferedReader(
   *       new InputStreamReader(new FileInputStream(file), "utf-8")
   *     ))
   *
   *     acquire.bracket { in =>
   *       // Usage part
   *       IO {
   *         // Yes, ugly Java, non-FP loop;
   *         // side-effects are suspended though
   *         var line: String = null
   *         val buff = new StringBuilder()
   *         do {
   *           line = in.readLine()
   *           if (line != null) buff.append(line)
   *         } while (line != null)
   *         buff.toString()
   *       }
   *     } { in =>
   *       // The release part
   *       IO(in.close())
   *     }
   *   }
   * }}}
   *
   * Note that in case of cancelation the underlying implementation cannot guarantee that the
   * computation described by `use` doesn't end up executed concurrently with the computation
   * from `release`. In the example above that ugly Java loop might end up reading from a
   * `BufferedReader` that is already closed due to the task being canceled, thus triggering an
   * error in the background with nowhere to get signaled.
   *
   * In this particular example, given that we are just reading from a file, it doesn't matter.
   * But in other cases it might matter, as concurrency on top of the JVM when dealing with I/O
   * might lead to corrupted data.
   *
   * For those cases you might want to do synchronization (e.g. usage of locks and semaphores)
   * and you might want to use [[bracketCase]], the version that allows you to differentiate
   * between normal termination and cancelation.
   *
   * '''NOTE on error handling''': in case both the `release` function and the `use` function
   * throws, the error raised by `release` gets signaled.
   *
   * For example:
   *
   * {{{
   *   val foo = new RuntimeException("Foo")
   *   val bar = new RuntimeException("Bar")
   *   IO("resource").bracket { _ =>
   *     // use
   *     IO.raiseError(foo)
   *   } { _ =>
   *     // release
   *     IO.raiseError(bar)
   *   }
   * }}}
   *
   * In this case the resulting `IO` will raise error `foo`, while the `bar` error gets reported
   * on a side-channel. This is consistent with the behavior of Java's "Try with resources"
   * except that no involved exceptions are mutated (i.e., in contrast to Java, `bar` isn't
   * added as a suppressed exception to `foo`).
   *
   * @see
   *   [[bracketCase]]
   *
   * @param use
   *   is a function that evaluates the resource yielded by the source, yielding a result that
   *   will get generated by the task returned by this `bracket` function
   *
   * @param release
   *   is a function that gets called after `use` terminates, either normally or in error, or if
   *   it gets canceled, receiving as input the resource that needs to be released
   */
  def bracket[B](use: A => IO[B])(release: A => IO[Unit]): IO[B] =
    bracketCase(use)((a, _) => release(a))

  /**
   * Returns a new `IO` task that treats the source task as the acquisition of a resource, which
   * is then exploited by the `use` function and then `released`, with the possibility of
   * distinguishing between normal termination and cancelation, such that an appropriate release
   * of resources can be executed.
   *
   * The `bracketCase` operation is the equivalent of `try {} catch {} finally {}` statements
   * from mainstream languages when used for the acquisition and release of resources.
   *
   * The `bracketCase` operation installs the necessary exception handler to release the
   * resource in the event of an exception being raised during the computation, or in case of
   * cancelation.
   *
   * In comparison with the simpler [[bracket]] version, this one allows the caller to
   * differentiate between normal termination, termination in error and cancelation via an
   * [[Outcome]] parameter.
   *
   * @see
   *   [[bracket]]
   *
   * @param use
   *   is a function that evaluates the resource yielded by the source, yielding a result that
   *   will get generated by this function on evaluation
   *
   * @param release
   *   is a function that gets called after `use` terminates, either normally or in error, or if
   *   it gets canceled, receiving as input the resource that needs release, along with the
   *   result of `use` (cancelation, error or successful result)
   */
  def bracketCase[B](use: A => IO[B])(release: (A, OutcomeIO[B]) => IO[Unit]): IO[B] =
    IO.bracketFull(_ => this)(use)(release)

  /**
   * Shifts the execution of the current IO to the specified `ExecutionContext`. All stages of
   * the execution will default to the pool in question, and any asynchronous callbacks will
   * shift back to the pool upon completion. Any nested use of `evalOn` will override the
   * specified pool. Once the execution fully completes, default control will be shifted back to
   * the enclosing (inherited) pool.
   *
   * @see
   *   [[IO.executionContext]] for obtaining the `ExecutionContext` on which the current `IO` is
   *   being executed
   */
  def evalOn(ec: ExecutionContext): IO[A] = IO.EvalOn(this, ec)

  /**
   * Shifts the execution of the current IO to the specified [[java.util.concurrent.Executor]].
   *
   * @see
   *   [[evalOn]]
   */
  def evalOnExecutor(executor: Executor): IO[A] =
    IO.asyncForIO.evalOnExecutor(this, executor)

  def startOn(ec: ExecutionContext): IO[FiberIO[A @uncheckedVariance]] = start.evalOn(ec)

  def startOnExecutor(executor: Executor): IO[FiberIO[A @uncheckedVariance]] =
    IO.asyncForIO.startOnExecutor(this, executor)

  def backgroundOn(ec: ExecutionContext): ResourceIO[IO[OutcomeIO[A @uncheckedVariance]]] =
    Resource.make(startOn(ec))(_.cancel).map(_.join)

  def backgroundOnExecutor(
      executor: Executor): ResourceIO[IO[OutcomeIO[A @uncheckedVariance]]] =
    IO.asyncForIO.backgroundOnExecutor(this, executor)

  /**
   * Given an effect which might be [[uncancelable]] and a finalizer, produce an effect which
   * can be canceled by running the finalizer. This combinator is useful for handling scenarios
   * in which an effect is inherently uncancelable but may be canceled through setting some
   * external state. A trivial example of this might be the following:
   *
   * {{{
   *   val flag = new AtomicBoolean(false)
   *   val ioa = IO blocking {
   *     while (!flag.get()) {
   *       Thread.sleep(10)
   *     }
   *   }
   *
   *   ioa.cancelable(IO.delay(flag.set(true)))
   * }}}
   *
   * Without `cancelable`, effects constructed by `blocking`, `delay`, and similar are
   * inherently uncancelable. Simply adding an `onCancel` to such effects is insufficient to
   * resolve this, despite the fact that under *some* circumstances (such as the above), it is
   * possible to enrich an otherwise-uncancelable effect with early termination. `cancelable`
   * addresses this use-case.
   *
   * Note that there is no free lunch here. If an effect truly cannot be prematurely terminated,
   * `cancelable` will not allow for cancelation. As an example, if you attempt to cancel
   * `uncancelable(_ => never)`, the cancelation will hang forever (in other words, it will be
   * itself equivalent to `never`). Applying `cancelable` will not change this in any way. Thus,
   * attempting to cancel `cancelable(uncancelable(_ => never), unit)` will ''also'' hang
   * forever. As in all cases, cancelation will only return when all finalizers have run and the
   * fiber has fully terminated.
   *
   * If the `IO` self-cancels and the `cancelable` itself is uncancelable, the resulting fiber
   * will be equal to `never` (similar to [[race]]). Under normal circumstances, if `IO`
   * self-cancels, that cancelation will be propagated to the calling context.
   *
   * @param fin
   *   an effect which orchestrates some external state which terminates the `IO`
   * @see
   *   [[uncancelable]]
   * @see
   *   [[onCancel]]
   */
  def cancelable(fin: IO[Unit]): IO[A] =
    Spawn[IO].cancelable(this, fin)

  def forceR[B](that: IO[B]): IO[B] =
    // cast is needed here to trick the compiler into avoiding the IO[Any]
    asInstanceOf[IO[Unit]].handleError(_ => ()).productR(that)

  /**
   * Monadic bind on `IO`, used for sequentially composing two `IO` actions, where the value
   * produced by the first `IO` is passed as input to a function producing the second `IO`
   * action.
   *
   * Due to this operation's signature, `flatMap` forces a data dependency between two `IO`
   * actions, thus ensuring sequencing (e.g. one action to be executed before another one).
   *
   * Any exceptions thrown within the function will be caught and sequenced into the `IO`,
   * because due to the nature of asynchronous processes, without catching and handling
   * exceptions, failures would be completely silent and `IO` references would never terminate
   * on evaluation.
   */
  def flatMap[B](f: A => IO[B]): IO[B] =
    IO.FlatMap(this, f, Tracing.calculateTracingEvent(f))

  def flatten[B](implicit ev: A <:< IO[B]): IO[B] = flatMap(ev)

  def flatTap[B](f: A => IO[B]): IO[A] = flatMap(a => f(a).as(a))

  /**
   * Executes the given `finalizer` when the source is finished, either in success or in error,
   * or if canceled.
   *
   * This variant of [[guaranteeCase]] evaluates the given `finalizer` regardless of how the
   * source gets terminated:
   *
   *   - normal completion
   *   - completion in error
   *   - cancelation
   *
   * This equivalence always holds:
   *
   * {{{
   *   io.guarantee(f) <-> IO.unit.bracket(_ => io)(_ => f)
   * }}}
   *
   * @see
   *   [[guaranteeCase]] for the version that can discriminate between termination conditions
   */
  def guarantee(finalizer: IO[Unit]): IO[A] =
    // this is a little faster than the default implementation, which helps Resource
    IO uncancelable { poll =>
      val onError: PartialFunction[Throwable, IO[Unit]] = { case _ => finalizer.reportError }
      poll(this).onCancel(finalizer).onError(onError).flatTap(_ => finalizer)
    }

  /**
   * Executes the given `finalizer` when the source is finished, either in success or in error,
   * or if canceled, allowing for differentiating between exit conditions.
   *
   * This variant of [[guarantee]] injects an [[Outcome]] in the provided function, allowing one
   * to make a difference between:
   *
   *   - normal completion
   *   - completion in error
   *   - cancelation
   *
   * This equivalence always holds:
   *
   * {{{
   *   io.guaranteeCase(f) <-> IO.unit.bracketCase(_ => io)((_, e) => f(e))
   * }}}
   *
   * @see
   *   [[guarantee]] for the simpler version
   */
  def guaranteeCase(finalizer: OutcomeIO[A @uncheckedVariance] => IO[Unit]): IO[A] =
    IO.uncancelable { poll =>
      val finalized = poll(this).onCancel(finalizer(Outcome.canceled))
      val onError: PartialFunction[Throwable, IO[Unit]] = {
        case e => finalizer(Outcome.errored(e)).reportError
      }
      finalized.onError(onError).flatTap { (a: A) => finalizer(Outcome.succeeded(IO.pure(a))) }
    }

  def handleError[B >: A](f: Throwable => B): IO[B] =
    handleErrorWith[B](t => IO.pure(f(t)))

  /**
   * Runs the current IO, if it fails with an error(exception), the other IO will be executed.
   * @param other
   *   IO to be executed (if the current IO fails)
   * @return
   */
  def orElse[B >: A](other: => IO[B]): IO[B] =
    handleErrorWith(_ => other)

  /**
   * Handle any error, potentially recovering from it, by mapping it to another `IO` value.
   *
   * Implements `ApplicativeError.handleErrorWith`.
   */
  def handleErrorWith[B >: A](f: Throwable => IO[B]): IO[B] =
    IO.HandleErrorWith(this, f, Tracing.calculateTracingEvent(f))

  /**
   * Recover from certain errors by mapping them to an `A` value.
   *
   * Implements `ApplicativeError.recover`.
   */
  def recover[B >: A](pf: PartialFunction[Throwable, B]): IO[B] =
    handleErrorWith(e => pf.andThen(IO.pure(_)).applyOrElse(e, IO.raiseError[A]))

  /**
   * Recover from certain errors by mapping them to another `IO` value.
   *
   * Implements `ApplicativeError.recoverWith`.
   */
  def recoverWith[B >: A](pf: PartialFunction[Throwable, IO[B]]): IO[B] =
    handleErrorWith(e => pf.applyOrElse(e, IO.raiseError))

  def ifM[B](ifTrue: => IO[B], ifFalse: => IO[B])(implicit ev: A <:< Boolean): IO[B] =
    flatMap(a => if (ev(a)) ifTrue else ifFalse)

  /**
   * Functor map on `IO`. Given a mapping function, it transforms the value produced by the
   * source, while keeping the `IO` context.
   *
   * Any exceptions thrown within the function will be caught and sequenced into the `IO`. Due
   * to the nature of asynchronous processes, without catching and handling exceptions, failures
   * would be completely silent and `IO` references would never terminate on evaluation.
   */
  def map[B](f: A => B): IO[B] = IO.Map(this, f, Tracing.calculateTracingEvent(f))

  /**
   * Applies rate limiting to this `IO` based on provided backpressure semantics.
   *
   * @return
   *   an Option which denotes if this `IO` was run or not according to backpressure semantics
   */
  def metered(backpressure: Backpressure[IO]): IO[Option[A]] =
    backpressure.metered(this)

  def onCancel(fin: IO[Unit]): IO[A] =
    IO.OnCancel(this, fin)

  @deprecated("Use onError with PartialFunction argument", "3.6.0")
  def onError(f: Throwable => IO[Unit]): IO[A] = {
    val pf: PartialFunction[Throwable, IO[Unit]] = { case t => f(t).reportError }
    onError(pf)
  }

  /**
   * Execute a callback on certain errors, then rethrow them. Any non matching error is rethrown
   * as well.
   *
   * Implements `ApplicativeError.onError`.
   */
  def onError(pf: PartialFunction[Throwable, IO[Unit]]): IO[A] =
    handleErrorWith(t => pf.applyOrElse(t, (_: Throwable) => IO.unit) *> IO.raiseError(t))

  /**
   * Like `Parallel.parProductL`
   */
  def parProductL[B](iob: IO[B])(implicit P: NonEmptyParallel[IO]): IO[A] =
    P.parProductL[A, B](this)(iob)

  /**
   * Like `Parallel.parProductR`
   */
  def parProductR[B](iob: IO[B])(implicit P: NonEmptyParallel[IO]): IO[B] =
    P.parProductR[A, B](this)(iob)

  /**
   * Like `Parallel.parProduct`
   */
  def parProduct[B](iob: IO[B])(implicit P: NonEmptyParallel[IO]): IO[(A, B)] =
    Parallel.parProduct(this, iob)(P)

  /**
   * Like `Parallel.parReplicateA`
   */
  def parReplicateA(n: Int): IO[List[A]] =
    List.fill(n)(this).parSequence

  /**
   * Like `Parallel.parReplicateA_`
   */
  def parReplicateA_(n: Int): IO[Unit] =
    List.fill(n)(this).parSequence_

  def race[B](that: IO[B]): IO[Either[A, B]] =
    IO.race(this, that)

  def raceOutcome[B](that: IO[B]): IO[Either[OutcomeIO[A @uncheckedVariance], OutcomeIO[B]]] =
    IO.asyncForIO.raceOutcome(this, that)

  def racePair[B](that: IO[B]): IO[Either[
    (OutcomeIO[A @uncheckedVariance], FiberIO[B]),
    (FiberIO[A @uncheckedVariance], OutcomeIO[B])]] =
    IO.racePair(this, that)

  /**
   * Inverse of `attempt`
   *
   * This function raises any materialized error.
   *
   * {{{
   * IO(Right(a)).rethrow === IO.pure(a)
   * IO(Left(ex)).rethrow === IO.raiseError(ex)
   *
   * // Or more generally:
   * io.attempt.rethrow === io // For any io.
   * }}}
   *
   * @see
   *   [[IO.raiseError]]
   * @see
   *   [[attempt]]
   */
  def rethrow[B](implicit ev: A <:< Either[Throwable, B]): IO[B] =
    flatMap(a => IO.fromEither(ev(a)))

  /**
   * Returns a new value that transforms the result of the source, given the `recover` or `map`
   * functions, which get executed depending on whether the result ends in error or if it is
   * successful.
   *
   * This is an optimization on usage of [[attempt]] and [[map]], this equivalence being true:
   *
   * {{{
   *   io.redeem(recover, map) <-> io.attempt.map(_.fold(recover, map))
   * }}}
   *
   * Usage of `redeem` subsumes `handleError` because:
   *
   * {{{
   *   io.redeem(fe, id) <-> io.handleError(fe)
   * }}}
   *
   * @param recover
   *   is a function used for error recover in case the source ends in error
   * @param map
   *   is a function used for mapping the result of the source in case it ends in success
   */
  def redeem[B](recover: Throwable => B, map: A => B): IO[B] =
    attempt.map(_.fold(recover, map))

  /**
   * Returns a new value that transforms the result of the source, given the `recover` or `bind`
   * functions, which get executed depending on whether the result ends in error or if it is
   * successful.
   *
   * This is an optimization on usage of [[attempt]] and [[flatMap]], this equivalence being
   * available:
   *
   * {{{
   *   io.redeemWith(recover, bind) <-> io.attempt.flatMap(_.fold(recover, bind))
   * }}}
   *
   * Usage of `redeemWith` subsumes `handleErrorWith` because:
   *
   * {{{
   *   io.redeemWith(fe, F.pure) <-> io.handleErrorWith(fe)
   * }}}
   *
   * Usage of `redeemWith` also subsumes [[flatMap]] because:
   *
   * {{{
   *   io.redeemWith(F.raiseError, fs) <-> io.flatMap(fs)
   * }}}
   *
   * @param recover
   *   is the function that gets called to recover the source in case of error
   * @param bind
   *   is the function that gets to transform the source in case of success
   */
  def redeemWith[B](recover: Throwable => IO[B], bind: A => IO[B]): IO[B] =
    attempt.flatMap(_.fold(recover, bind))

  def replicateA(n: Int): IO[List[A]] =
    if (n <= 0)
      IO.pure(Nil)
    else
      flatMap(a => replicateA(n - 1).map(a :: _))

  def replicateA_(n: Int): IO[Unit] =
    if (n <= 0)
      IO.unit
    else
      flatMap(_ => replicateA_(n - 1))

  /**
   * Starts this `IO` on the supervisor.
   *
   * @return
   *   a [[cats.effect.kernel.Fiber]] that represents a handle to the started fiber.
   */
  def supervise(supervisor: Supervisor[IO]): IO[Fiber[IO, Throwable, A @uncheckedVariance]] =
    supervisor.supervise(this)

  /**
   * Logs the value of this `IO` _(even if it is an error or if it was cancelled)_ to the
   * standard output, using the implicit `cats.Show` instance.
   *
   * This operation is intended as a quick debug, not as proper logging.
   *
   * @param prefix
   *   A custom prefix for the log message, `DEBUG` is used as the default.
   */
  def debug[B >: A](prefix: String = "DEBUG")(
      implicit S: Show[B] = Show.fromToString[B]): IO[A] =
    guaranteeCase {
      case Outcome.Succeeded(ioa) =>
        ioa.flatMap(a => IO.println(s"${prefix}: Succeeded: ${S.show(a)}"))

      case Outcome.Errored(ex) =>
        IO.println(s"${prefix}: Errored: ${ex}")

      case Outcome.Canceled() =>
        IO.println(s"${prefix}: Canceled")
    }

  /**
   * Returns an IO that will delay the execution of the source by the given duration.
   *
   * @param duration
   *   The duration to wait before executing the source
   */
  def delayBy(duration: Duration): IO[A] =
    IO.sleep(duration) *> this

  private[effect] def delayBy(duration: FiniteDuration): IO[A] =
    delayBy(duration: Duration)

  /**
   * Returns an IO that will wait for the given duration after the execution of the source
   * before returning the result.
   *
   * @param duration
   *   The duration to wait after executing the source
   */
  def andWait(duration: Duration): IO[A] =
    this <* IO.sleep(duration)

  private[effect] def andWait(duration: FiniteDuration): IO[A] =
    andWait(duration: Duration)

  /**
   * Returns an IO that either completes with the result of the source within the specified time
   * `duration` or otherwise raises a `TimeoutException`.
   *
   * The source is canceled in the event that it takes longer than the specified time duration
   * to complete. Once the source has been successfully canceled (and has completed its
   * finalizers), the `TimeoutException` will be raised. If the source is uncancelable, the
   * resulting effect will wait for it to complete before raising the exception.
   *
   * @param duration
   *   is the time span for which we wait for the source to complete; in the event that the
   *   specified time has passed without the source completing, a `TimeoutException` is raised
   */
  def timeout[A2 >: A](duration: Duration): IO[A2] =
    handleDuration(duration, this) { finiteDuration =>
      timeoutTo(
        finiteDuration,
        IO.defer(IO.raiseError(new TimeoutException(finiteDuration.toString))))
    }

  private[effect] def timeout(duration: FiniteDuration): IO[A] =
    timeout(duration: Duration)

  /**
   * Returns an IO that either completes with the result of the source within the specified time
   * `duration` or otherwise evaluates the `fallback`.
   *
   * The source is canceled in the event that it takes longer than the specified time duration
   * to complete. Once the source has been successfully canceled (and has completed its
   * finalizers), the fallback will be sequenced. If the source is uncancelable, the resulting
   * effect will wait for it to complete before evaluating the fallback.
   *
   * @param duration
   *   is the time span for which we wait for the source to complete; in the event that the
   *   specified time has passed without the source completing, the `fallback` gets evaluated
   *
   * @param fallback
   *   is the task evaluated after the duration has passed and the source canceled
   */
  def timeoutTo[A2 >: A](duration: Duration, fallback: IO[A2]): IO[A2] = {
    handleDuration[IO[A2]](duration, this) { finiteDuration =>
      race(IO.sleep(finiteDuration)).flatMap {
        case Right(_) => fallback
        case Left(value) => IO.pure(value)
      }
    }
  }

  private[effect] def timeoutTo[A2 >: A](duration: FiniteDuration, fallback: IO[A2]): IO[A2] =
    timeoutTo(duration: Duration, fallback)

  /**
   * Returns an IO that either completes with the result of the source within the specified time
   * `duration` or otherwise raises a `TimeoutException`.
   *
   * The source is canceled in the event that it takes longer than the specified time duration
   * to complete. Unlike [[timeout]], the cancelation of the source will be ''requested'' but
   * not awaited, and the exception will be raised immediately upon the completion of the timer.
   * This may more closely match intuitions about timeouts, but it also violates backpressure
   * guarantees and intentionally leaks fibers.
   *
   * This combinator should be applied very carefully.
   *
   * @param duration
   *   The time span for which we wait for the source to complete; in the event that the
   *   specified time has passed without the source completing, a `TimeoutException` is raised
   * @see
   *   [[timeout]] for a variant which respects backpressure and does not leak fibers
   */
  def timeoutAndForget(duration: Duration): IO[A] =
    Temporal[IO].timeoutAndForget(this, duration)

  private[effect] def timeoutAndForget(duration: FiniteDuration): IO[A] =
    timeoutAndForget(duration: Duration)

  def timed: IO[(FiniteDuration, A)] =
    Clock[IO].timed(this)

  /**
   * Lifts this `IO` into a resource. The resource has a no-op release.
   */
  def toResource: Resource[IO, A] = Resource.eval(this)

  def product[B](that: IO[B]): IO[(A, B)] =
    flatMap(a => that.map(b => (a, b)))

  def productL[B](that: IO[B]): IO[A] =
    flatMap(a => that.as(a))

  def productR[B](that: IO[B]): IO[B] =
    flatMap(_ => that)

  /**
   * Start execution of the source suspended in the `IO` context.
   *
   * This can be used for non-deterministic / concurrent execution. The following code is more
   * or less equivalent with `parMap2` (minus the behavior on error handling and cancelation):
   *
   * {{{
   *   def par2[A, B](ioa: IO[A], iob: IO[B]): IO[(A, B)] =
   *     for {
   *       fa <- ioa.start
   *       fb <- iob.start
   *         a <- fa.join
   *         b <- fb.join
   *     } yield (a, b)
   * }}}
   *
   * Note in such a case usage of `parMapN` (via `cats.Parallel`) is still recommended because
   * of behavior on error and cancelation — consider in the example above what would happen if
   * the first task finishes in error. In that case the second task doesn't get canceled, which
   * creates a potential memory leak.
   *
   * Also see [[background]] for a safer alternative.
   */
  def start: IO[FiberIO[A @uncheckedVariance]] =
    IO.Start(this)

  /**
   * Returns a resource that will start execution of this IO in the background.
   *
   * In case the resource is closed while this IO is still running (e.g. due to a failure in
   * `use`), the background action will be canceled.
   *
   * @see
   *   [[cats.effect.kernel.GenSpawn#background]] for the generic version.
   */
  def background: ResourceIO[IO[OutcomeIO[A @uncheckedVariance]]] =
    Spawn[IO].background(this)

  def memoize: IO[IO[A]] =
    Concurrent[IO].memoize(this)

  /**
   * Makes the source `IO` uninterruptible such that a [[cats.effect.kernel.Fiber#cancel]]
   * signal is ignored until completion.
   *
   * @see
   *   [[IO.uncancelable]] for constructing uncancelable `IO` values with user-configurable
   *   cancelable regions
   */
  def uncancelable: IO[A] =
    IO.uncancelable(_ => this)

  /**
   * Ignores the result of this IO.
   */
  def void: IO[Unit] =
    map(_ => ())

  /**
   * Similar to [[IO.voidError]], but also reports the error.
   */
  private[effect] def reportError(implicit ev: A <:< Unit): IO[Unit] = {
    val _ = ev
    asInstanceOf[IO[Unit]].handleErrorWith { t =>
      IO.executionContext.flatMap(ec => IO(ec.reportFailure(t)))
    }
  }

  /**
   * Discard any error raised by the source.
   */
  def voidError(implicit ev: A <:< Unit): IO[Unit] = {
    val _ = ev
    asInstanceOf[IO[Unit]].handleError(_ => ())
  }

  /**
   * Converts the source `IO` into any `F` type that implements the [[LiftIO]] type class.
   */
  def to[F[_]](implicit F: LiftIO[F]): F[A @uncheckedVariance] =
    F.liftIO(this)

  override def toString: String = "IO(...)"

  // unsafe stuff

  /**
   * Passes the result of the encapsulated effects to the given callback by running them as
   * impure side effects.
   *
   * Any exceptions raised within the effect will be passed to the callback in the `Either`. The
   * callback will be invoked at most *once*. In addition, fatal errors will be printed. Note
   * that it is very possible to construct an IO which never returns while still never blocking
   * a thread, and attempting to evaluate that IO with this method will result in a situation
   * where the callback is *never* invoked.
   *
   * As the name says, this is an UNSAFE function as it is impure and performs side effects. You
   * should ideally only call this function ''once'', at the very end of your program.
   */
  def unsafeRunAsync(cb: Either[Throwable, A] => Unit)(
      implicit runtime: unsafe.IORuntime): Unit = {
    unsafeRunFiber(
      cb(Left(new CancellationException("The fiber was canceled"))),
      t => {
        if (!NonFatal(t)) {
          t.printStackTrace()
        }
        cb(Left(t))
      },
      a => cb(Right(a))
    )
    ()
  }

  def unsafeRunAsyncOutcome(cb: Outcome[Id, Throwable, A @uncheckedVariance] => Unit)(
      implicit runtime: unsafe.IORuntime): Unit = {
    unsafeRunFiber(
      cb(Outcome.canceled),
      t => {
        if (!NonFatal(t)) {
          t.printStackTrace()
        }
        cb(Outcome.errored(t))
      },
      a => cb(Outcome.succeeded(a: Id[A])))
    ()
  }

  /**
   * Triggers the evaluation of the source and any suspended side effects therein, but ignores
   * the result.
   *
   * This operation is similar to [[unsafeRunAsync]], in that the evaluation can happen
   * asynchronously, except no callback is required and therefore the result is ignored.
   *
   * Note that errors still get logged (via IO's internal logger), because errors being thrown
   * should never be totally silent.
   */
  def unsafeRunAndForget()(implicit runtime: unsafe.IORuntime): Unit = {
    val _ = unsafeRunFiber(
      (),
      t => {
        if (NonFatal(t)) {
          if (runtime.config.reportUnhandledFiberErrors)
            runtime.compute.reportFailure(t)
        } else { t.printStackTrace() }
      },
      _ => ())
    ()
  }

  // internally used for error reporting
  private[effect] def unsafeRunAndForgetWithoutCallback()(
      implicit runtime: unsafe.IORuntime): Unit = {
    val _ = unsafeRunFiber((), _ => (), _ => (), false)
    ()
  }

  /**
   * Evaluates the effect and produces the result in a `Future`.
   *
   * This is similar to `unsafeRunAsync` in that it evaluates the `IO` as a side effect in a
   * non-blocking fashion, but uses a `Future` rather than an explicit callback. This function
   * should really only be used if interoperating with code which uses Scala futures.
   *
   * @see
   *   [[IO.fromFuture]]
   */
  def unsafeToFuture()(implicit runtime: unsafe.IORuntime): Future[A] =
    unsafeToFutureCancelable()._1

  /**
   * Evaluates the effect and produces the result in a `Future`, along with a cancelation token
   * that can be used to cancel the original effect.
   *
   * This is similar to `unsafeRunAsync` in that it evaluates the `IO` as a side effect in a
   * non-blocking fashion, but uses a `Future` rather than an explicit callback. This function
   * should really only be used if interoperating with code which uses Scala futures.
   *
   * @see
   *   [[IO.fromFuture]]
   */
  def unsafeToFutureCancelable()(
      implicit runtime: unsafe.IORuntime): (Future[A], () => Future[Unit]) = {
    val p = Promise[A]()

    val fiber = unsafeRunFiber(
      p.failure(new CancellationException("The fiber was canceled")),
      p.failure,
      p.success)

    (p.future, () => fiber.cancel.unsafeToFuture())
  }

  /**
   * Evaluates the effect, returning a cancelation token that can be used to cancel it.
   *
   * This is similar to `unsafeRunAsync` in that it evaluates the `IO` as a side effect in a
   * non-blocking fashion, but uses a `Future` rather than an explicit callback. This function
   * should really only be used if interoperating with code which uses Scala futures.
   *
   * @see
   *   [[IO.fromFuture]]
   */
  def unsafeRunCancelable()(implicit runtime: unsafe.IORuntime): () => Future[Unit] =
    unsafeToFutureCancelable()._2

  private[effect] def unsafeRunFiber(
      canceled: => Unit,
      failure: Throwable => Unit,
      success: A => Unit,
      registerCallback: Boolean = true)(
      implicit runtime: unsafe.IORuntime): IOFiber[A @uncheckedVariance] = {

    val fiber = new IOFiber[A](
      Map.empty,
      oc =>
        oc.fold(
          {
            runtime.fiberErrorCbs.remove(failure)
            canceled
          },
          { t =>
            runtime.fiberErrorCbs.remove(failure)
            failure(t)
          },
          { ioa =>
            runtime.fiberErrorCbs.remove(failure)
            success(ioa.asInstanceOf[IO.Pure[A]].value)
          }
        ),
      this,
      runtime.compute,
      runtime
    )

    if (registerCallback) {
      runtime.fiberErrorCbs.put(failure)
    }

    runtime.compute.execute(fiber)
    fiber
  }

  @deprecated("use syncStep(Int) instead", "3.4.0")
  def syncStep: SyncIO[Either[IO[A], A]] = syncStep(Int.MaxValue)

  /**
   * Translates this `IO[A]` into a `SyncIO` value which, when evaluated, runs the original `IO`
   * to its completion, the `limit` number of stages, or until the first stage that cannot be
   * expressed with `SyncIO` (typically an asynchronous boundary).
   *
   * @param limit
   *   The maximum number of stages to evaluate prior to forcibly yielding to `IO`
   */
  def syncStep(limit: Int): SyncIO[Either[IO[A], A]] =
    IO.asyncForIO.syncStep[SyncIO, A](this, limit)

  /**
   * Evaluates the current `IO` in an infinite loop, terminating only on error or cancelation.
   *
   * {{{
   *   IO.println("Hello, World!").foreverM    // continues printing forever
   * }}}
   */
  def foreverM: IO[Nothing] = IO.asyncForIO.foreverM[A, Nothing](this)

  def whileM[G[_]: Alternative, B >: A](p: IO[Boolean]): IO[G[B]] =
    Monad[IO].whileM[G, B](p)(this)

  def whileM_(p: IO[Boolean]): IO[Unit] = Monad[IO].whileM_(p)(this)

  def untilM[G[_]: Alternative, B >: A](cond: => IO[Boolean]): IO[G[B]] =
    Monad[IO].untilM[G, B](this)(cond)

  def untilM_(cond: => IO[Boolean]): IO[Unit] = Monad[IO].untilM_(this)(cond)

  def iterateWhile(p: A => Boolean): IO[A] = Monad[IO].iterateWhile(this)(p)

  def iterateUntil(p: A => Boolean): IO[A] = Monad[IO].iterateUntil(this)(p)
}

private[effect] trait IOLowPriorityImplicits {

  implicit def showForIONoPure[A]: Show[IO[A]] =
    Show.show(_ => "IO(...)")

  implicit def semigroupForIO[A: Semigroup]: Semigroup[IO[A]] =
    new IOSemigroup[A]

  protected class IOSemigroup[A](implicit val A: Semigroup[A]) extends Semigroup[IO[A]] {
    def combine(left: IO[A], right: IO[A]) =
      left.flatMap(l => right.map(r => l |+| r))
  }
}

object IO extends IOCompanionPlatform with IOLowPriorityImplicits with TupleParallelSyntax {

  implicit final def catsSyntaxParallelSequence1[T[_], A](
      toia: T[IO[A]]): ParallelSequenceOps1[T, IO, A] = new ParallelSequenceOps1(toia)

  implicit final def catsSyntaxParallelSequence_[T[_], A](
      tioa: T[IO[A]]): ParallelSequence_Ops[T, IO, A] =
    new ParallelSequence_Ops(tioa)

  implicit final def catsSyntaxParallelUnorderedSequence[T[_], A](
      tioa: T[IO[A]]): ParallelUnorderedSequenceOps[T, IO, A] =
    new ParallelUnorderedSequenceOps(tioa)

  implicit final def catsSyntaxParallelFlatSequence1[T[_], A](
      tioa: T[IO[T[A]]]): ParallelFlatSequenceOps1[T, IO, A] =
    new ParallelFlatSequenceOps1(tioa)

  implicit final def catsSyntaxParallelUnorderedFlatSequence[T[_], A](
      tiota: T[IO[T[A]]]): ParallelUnorderedFlatSequenceOps[T, IO, A] =
    new ParallelUnorderedFlatSequenceOps(tiota)

  implicit final def catsSyntaxParallelSequenceFilter[T[_], A](
      x: T[IO[Option[A]]]): ParallelSequenceFilterOps[T, IO, A] =
    new ParallelSequenceFilterOps(x)

  implicit class IOFlatSequenceOps[T[_], A](tiota: T[IO[T[A]]]) {
    def flatSequence(
        implicit T: Traverse[T],
        G: Applicative[IO],
        F: cats.FlatMap[T]): IO[T[A]] = {
      tiota.sequence(T, G).map(F.flatten)
    }
  }

  implicit class IOSequenceOps[T[_], A](tioa: T[IO[A]]) {
    def sequence(implicit T: Traverse[T], G: Applicative[IO]): IO[T[A]] = T.sequence(tioa)(G)

    def sequence_(implicit F: Foldable[T], G: Applicative[IO]): IO[Unit] = F.sequence_(tioa)(G)
  }

  @static private[this] val _alignForIO = new IOAlign
  @static private[this] val _asyncForIO: kernel.Async[IO] = new IOAsync

  /**
   * Newtype encoding for an `IO` datatype that has a `cats.Applicative` capable of doing
   * parallel processing in `ap` and `map2`, needed for implementing `cats.Parallel`.
   *
   * For converting back and forth you can use either the `Parallel[IO]` instance or the methods
   * `cats.effect.kernel.Par.ParallelF.apply` for wrapping any `IO` value and
   * `cats.effect.kernel.Par.ParallelF.value` for unwrapping it.
   *
   * The encoding is based on the "newtypes" project by Alexander Konovalov, chosen because it's
   * devoid of boxing issues and a good choice until opaque types will land in Scala.
   */
  type Par[A] = ParallelF[IO, A]

  implicit def commutativeApplicativeForIOPar: CommutativeApplicative[IO.Par] =
    instances.spawn.commutativeApplicativeForParallelF

  implicit def alignForIOPar: Align[IO.Par] =
    instances.spawn.alignForParallelF

  // constructors

  /**
   * Suspends a synchronous side effect in `IO`. Use [[IO.apply]] if your side effect is not
   * thread-blocking; otherwise you should use [[IO.blocking]] (uncancelable) or
   * `IO.interruptible` (cancelable).
   *
   * Alias for [[IO.delay]].
   */
  def apply[A](thunk: => A): IO[A] = delay(thunk)

  /**
   * Suspends a synchronous side effect in `IO`. Use [[IO.delay]] if your side effect is not
   * thread-blocking; otherwise you should use [[IO.blocking]] (uncancelable) or
   * `IO.interruptible` (cancelable).
   *
   * Any exceptions thrown by the effect will be caught and sequenced into the `IO`.
   */
  def delay[A](thunk: => A): IO[A] = {
    val fn = () => thunk
    Delay(fn, Tracing.calculateTracingEvent(fn))
  }

  /**
   * Suspends a synchronous side effect which produces an `IO` in `IO`.
   *
   * This is useful for trampolining (i.e. when the side effect is conceptually the allocation
   * of a stack frame). Any exceptions thrown by the side effect will be caught and sequenced
   * into the `IO`.
   */
  def defer[A](thunk: => IO[A]): IO[A] =
    delay(thunk).flatten

  /**
   * Suspends an asynchronous side effect with optional immediate result in `IO`.
   *
   * The given function `k` will be invoked during evaluation of the `IO` to:
   *   - check if result is already available;
   *   - "schedule" the asynchronous callback, where the callback of type `Either[Throwable, A]
   *     \=> Unit` is the parameter passed to that function. Only the ''first'' invocation of
   *     the callback will be effective! All subsequent invocations will be silently dropped.
   *
   * The process of registering the callback itself is suspended in `IO` (the outer `IO` of
   * `IO[Either[Option[IO[Unit]], A]]`).
   *
   * The effect returns `Either[Option[IO[Unit]], A]` where:
   *   - right side `A` is an immediate result of computation (callback invocation will be
   *     dropped);
   *   - left side `Option[IO[Unit]] `is an optional finalizer to be run in the event that the
   *     fiber running `asyncCheckAttempt(k)` is canceled.
   *
   * For example, here is a simplified version of `IO.fromCompletableFuture`:
   *
   * {{{
   * def fromCompletableFuture[A](fut: IO[CompletableFuture[A]]): IO[A] = {
   *   fut.flatMap { cf =>
   *     IO.asyncCheckAttempt { cb =>
   *       if (cf.isDone) {
   *         //Register immediately available result of the completable future or handle an error
   *         IO(cf.get)
   *           .map(Right(_))
   *           .handleError { e =>
   *             cb(Left(e))
   *             Left(None)
   *           }
   *       } else {
   *         IO {
   *           //Invoke the callback with the result of the completable future
   *           val stage = cf.handle[Unit] {
   *             case (a, null) => cb(Right(a))
   *             case (_, e) => cb(Left(e))
   *           }
   *
   *           //Cancel the completable future if the fiber is canceled
   *           Left(Some(IO(stage.cancel(false)).void))
   *         }
   *       }
   *     }
   *   }
   * }
   * }}}
   *
   * Note that `asyncCheckAttempt` is uncancelable during its registration.
   *
   * @see
   *   [[async]] for a simplified variant without an option for immediate result
   */
  def asyncCheckAttempt[A](
      k: (Either[Throwable, A] => Unit) => IO[Either[Option[IO[Unit]], A]]): IO[A] = {
    val body = new Cont[IO, A, A] {
      def apply[G[_]](implicit G: MonadCancel[G, Throwable]) = { (resume, get, lift) =>
        G.uncancelable { poll =>
          lift(k(resume)) flatMap {
            case Right(a) => G.pure(a)
            case Left(Some(fin)) => G.onCancel(poll(get), lift(fin))
            case Left(None) => get
          }
        }
      }
    }

    IOCont(body, Tracing.calculateTracingEvent(k))
  }

  /**
   * Suspends an asynchronous side effect in `IO`.
   *
   * The given function `k` will be invoked during evaluation of the `IO` to "schedule" the
   * asynchronous callback, where the callback of type `Either[Throwable, A] => Unit` is the
   * parameter passed to that function. Only the ''first'' invocation of the callback will be
   * effective! All subsequent invocations will be silently dropped.
   *
   * The process of registering the callback itself is suspended in `IO` (the outer `IO` of
   * `IO[Option[IO[Unit]]]`).
   *
   * The effect returns `Option[IO[Unit]]` which is an optional finalizer to be run in the event
   * that the fiber running `async(k)` is canceled.
   *
   * For example, here is a simplified version of `IO.fromCompletableFuture`:
   *
   * {{{
   * def fromCompletableFuture[A](fut: IO[CompletableFuture[A]]): IO[A] = {
   *   fut.flatMap { cf =>
   *     IO.async { cb =>
   *       IO {
   *         //Invoke the callback with the result of the completable future
   *         val stage = cf.handle[Unit] {
   *           case (a, null) => cb(Right(a))
   *           case (_, e) => cb(Left(e))
   *         }
   *
   *         //Cancel the completable future if the fiber is canceled
   *         Some(IO(stage.cancel(false)).void)
   *       }
   *     }
   *   }
   * }
   * }}}
   *
   * @note
   *   `async` is always uncancelable during its registration. The created effect will be
   *   uncancelable during its execution if the registration callback provides no finalizer
   *   (i.e. evaluates to `None`). If you need the created task to be cancelable, return a
   *   finalizer effect upon the registration. In a rare case when there's nothing to finalize,
   *   you can return `Some(IO.unit)` for that.
   *
   * @see
   *   [[async_]] for a simplified variant without a finalizer
   * @see
   *   [[asyncCheckAttempt]] for more generic version providing an optional immediate result of
   *   computation
   */
  def async[A](k: (Either[Throwable, A] => Unit) => IO[Option[IO[Unit]]]): IO[A] = {
    val body = new Cont[IO, A, A] {
      def apply[G[_]](implicit G: MonadCancel[G, Throwable]) = { (resume, get, lift) =>
        G.uncancelable { poll =>
          lift(k(resume)) flatMap {
            case Some(fin) => G.onCancel(poll(get), lift(fin))
            case None => get
          }
        }
      }
    }

    IOCont(body, Tracing.calculateTracingEvent(k))
  }

  /**
   * Suspends an asynchronous side effect in `IO`.
   *
   * The given function `k` will be invoked during evaluation of the `IO` to "schedule" the
   * asynchronous callback, where the callback is the parameter passed to that function. Only
   * the ''first'' invocation of the callback will be effective! All subsequent invocations will
   * be silently dropped.
   *
   * As a quick example, you can use this function to perform a parallel computation given an
   * `ExecutorService`:
   *
   * {{{
   * def fork[A](body: => A, exc: ExecutorService): IO[A] =
   *   IO async_ { cb =>
   *     exc.execute(new Runnable {
   *       def run() =
   *         try cb(Right(body)) catch { case t if NonFatal(t) => cb(Left(t)) }
   *     })
   *   }
   * }}}
   *
   * The `fork` function will do exactly what it sounds like: take a thunk and an
   * `ExecutorService` and run that thunk on the thread pool. Or rather, it will produce an `IO`
   * which will do those things when run; it does *not* schedule the thunk until the resulting
   * `IO` is run! Note that there is no thread blocking in this implementation; the resulting
   * `IO` encapsulates the callback in a pure and monadic fashion without using threads.
   *
   * This function can be thought of as a safer, lexically-constrained version of `Promise`,
   * where `IO` is like a safer, lazy version of `Future`.
   *
   * @note
   *   `async_` is uncancelable during both its registration and execution. If you need an
   *   asyncronous effect to be cancelable, consider using `async` instead.
   *
   * @see
   *   [[async]] for more generic version providing a finalizer
   * @see
   *   [[asyncCheckAttempt]] for more generic version providing an optional immediate result of
   *   computation and a finalizer
   */
  def async_[A](k: (Either[Throwable, A] => Unit) => Unit): IO[A] = {
    val body = new Cont[IO, A, A] {
      def apply[G[_]](implicit G: MonadCancel[G, Throwable]) = { (resume, get, lift) =>
        G.uncancelable(_ => lift(IO.delay(k(resume))).flatMap(_ => get))
      }
    }

    IOCont(body, Tracing.calculateTracingEvent(k))
  }

  /**
   * An effect that requests self-cancelation on the current fiber.
   *
   * `canceled` has a return type of `IO[Unit]` instead of `IO[Nothing]` due to execution
   * continuing in a masked region. In the following example, the fiber requests
   * self-cancelation in a masked region, so cancelation is suppressed until the fiber is
   * completely unmasked. `fa` will run but `fb` will not. If `canceled` had a return type of
   * `IO[Nothing]`, then it would not be possible to continue execution to `fa` (there would be
   * no `Nothing` value to pass to the `flatMap`).
   *
   * {{{
   *
   *   IO.uncancelable { _ =>
   *     IO.canceled *> fa
   *   } *> fb
   *
   * }}}
   */
  def canceled: IO[Unit] = Canceled

  /**
   * Introduces a fairness boundary that yields control back to the scheduler of the runtime
   * system. This allows the carrier thread to resume execution of another waiting fiber.
   *
   * This function is primarily useful when performing long-running computation that is outside
   * of the monadic context. For example:
   *
   * {{{
   *   fa.map(data => expensiveWork(data))
   * }}}
   *
   * In the above, we're assuming that `expensiveWork` is a function which is entirely
   * compute-bound but very long-running. A good rule of thumb is to consider a function
   * "expensive" when its runtime is around three or more orders of magnitude higher than the
   * overhead of the `map` function itself (which runs in around 5 nanoseconds on modern
   * hardware). Thus, any `expensiveWork` function which requires around 10 microseconds or
   * longer to execute should be considered "long-running".
   *
   * The danger is that these types of long-running actions outside of the monadic context can
   * result in degraded fairness properties. The solution is to add an explicit `cede` both
   * before and after the expensive operation:
   *
   * {{{
   *   (fa <* IO.cede).map(data => expensiveWork(data)).guarantee(IO.cede)
   * }}}
   *
   * Note that extremely long-running `expensiveWork` functions can still cause fairness issues,
   * even when used with `cede`. This problem is somewhat fundamental to the nature of
   * scheduling such computation on carrier threads. Whenever possible, it is best to break
   * apart any such functions into multiple pieces invoked independently (e.g. via chained `map`
   * calls) whenever the execution time exceeds five or six orders of magnitude beyond the
   * overhead of `map` itself (around 1 millisecond on most hardware).
   *
   * This operation is not ''required'' in most applications, particularly those which are
   * primarily I/O bound, as `IO` itself will automatically introduce fairness boundaries
   * without requiring user input. These automatic boundaries are controlled by the
   * [[cats.effect.unsafe.IORuntimeConfig.autoYieldThreshold]] configuration parameter, which in
   * turn may be adjusted by overriding [[IOApp.runtimeConfig]].
   */
  def cede: IO[Unit] = Cede

  /**
   * This is a low-level API which is meant for implementors, please use `background`, `start`,
   * `async`, or `Deferred` instead, depending on the use case
   */
  def cont[K, R](body: Cont[IO, K, R]): IO[R] =
    IOCont[K, R](body, Tracing.calculateTracingEvent(body))

  def executionContext: IO[ExecutionContext] = ReadEC

  def executor: IO[Executor] = _asyncForIO.executor

  def monotonic: IO[FiniteDuration] = Monotonic

  /**
   * A non-terminating `IO`, alias for `async(_ => ())`.
   */
  def never[A]: IO[A] = _never

  /**
   * An IO that contains an empty Option.
   *
   * @see
   *   [[some]] for the non-empty Option variant
   */
  def none[A]: IO[Option[A]] = pure(None)

  /**
   * An IO that contains some Option of the given value.
   *
   * @see
   *   [[none]] for the empty Option variant
   */
  def some[A](a: A): IO[Option[A]] = pure(Some(a))

  /**
   * Like `Parallel.parTraverse`
   */
  def parTraverse[T[_]: Traverse, A, B](ta: T[A])(f: A => IO[B]): IO[T[B]] =
    ta.parTraverse(f)

  /**
   * Like `Parallel.parTraverse_`
   */
  def parTraverse_[T[_]: Foldable, A, B](ta: T[A])(f: A => IO[B]): IO[Unit] =
    ta.parTraverse_(f)

  /**
   * Like `Parallel.parTraverse`, but limits the degree of parallelism.
   */
  def parTraverseN[T[_]: Traverse, A, B](n: Int)(ta: T[A])(f: A => IO[B]): IO[T[B]] =
    _asyncForIO.parTraverseN(n)(ta)(f)

  /**
   * Like `Parallel.parTraverse_`, but limits the degree of parallelism.
   */
  def parTraverseN_[T[_]: Foldable, A, B](n: Int)(ta: T[A])(f: A => IO[B]): IO[Unit] =
    _asyncForIO.parTraverseN_(n)(ta)(f)

  /**
   * Like `Parallel.parSequence`
   */
  def parSequence[T[_]: Traverse, A](tioa: T[IO[A]]): IO[T[A]] =
    tioa.parSequence

  /**
   * Like `Parallel.parSequence_`
   */
  def parSequence_[T[_]: Foldable, A](tioa: T[IO[A]]): IO[Unit] =
    tioa.parSequence_

  /**
   * Like `Parallel.parSequence`, but limits the degree of parallelism.
   */
  def parSequenceN[T[_]: Traverse, A](n: Int)(tioa: T[IO[A]]): IO[T[A]] =
    _asyncForIO.parSequenceN(n)(tioa)

  /**
   * Like `Parallel.parSequence_`, but limits the degree of parallelism.
   */
  def parSequenceN_[T[_]: Foldable, A](n: Int)(tma: T[IO[A]]): IO[Unit] =
    _asyncForIO.parSequenceN_(n)(tma)

  /**
   * Like `Parallel.parReplicateA`, but limits the degree of parallelism.
   */
  def parReplicateAN[A](n: Int)(replicas: Int, ioa: IO[A]): IO[List[A]] =
    _asyncForIO.parReplicateAN(n)(replicas, ioa)

  /**
   * Lifts a pure value into `IO`.
   *
   * This should ''only'' be used if the value in question has "already" been computed! In other
   * words, something like `IO.pure(readLine)` is most definitely not the right thing to do!
   * However, `IO.pure(42)` is correct and will be more efficient (when evaluated) than
   * `IO(42)`, due to avoiding the allocation of extra thunks.
   */
  def pure[A](value: A): IO[A] = Pure(value)

  /**
   * Constructs an `IO` which sequences the specified exception.
   *
   * If this `IO` is run using `unsafeRunSync` or `unsafeRunTimed`, the exception will be
   * thrown. This exception can be "caught" (or rather, materialized into value-space) using the
   * `attempt` method.
   *
   * @see
   *   [[IO#attempt]]
   */
  def raiseError[A](t: Throwable): IO[A] = Error(t)

  /**
   * @return
   *   a randomly-generated UUID
   *
   * This is equivalent to `UUIDGen[IO].randomUUID`, just provided as a method for convenience
   */
  def randomUUID: IO[UUID] = UUIDGen[IO].randomUUID

  def realTime: IO[FiniteDuration] = RealTime

  /**
   * Creates an asynchronous task that on evaluation sleeps for the specified duration, emitting
   * a notification on completion.
   *
   * This is the pure, non-blocking equivalent to:
   *
   *   - `Thread.sleep` (JVM)
   *   - `ScheduledExecutorService.schedule` (JVM)
   *   - `setTimeout` (JavaScript)
   *
   * You can combine it with `flatMap` to create delayed tasks:
   *
   * {{{
   *   val timeout = IO.sleep(10.seconds).flatMap { _ =>
   *     IO.raiseError(new TimeoutException)
   *   }
   * }}}
   *
   * The created task is cancelable and so it can be used safely in race conditions without
   * resource leakage.
   *
   * @param delay
   *   the time span to wait before emitting the tick
   *
   * @return
   *   a new asynchronous and cancelable `IO` that will sleep for the specified duration and
   *   then finally emit a tick
   */
  def sleep(delay: Duration): IO[Unit] =
    handleDuration[IO[Unit]](delay, IO.never)(Sleep(_))

  def sleep(finiteDelay: FiniteDuration): IO[Unit] =
    sleep(finiteDelay: Duration)

  def trace: IO[Trace] =
    IOTrace

  def traverse[T[_]: Traverse, A, B](ta: T[A])(f: A => IO[B]): IO[T[B]] =
    ta.traverse(f)(_asyncForIO)

  def traverse_[T[_]: Foldable, A, B](ta: T[A])(f: A => IO[B]): IO[Unit] =
    ta.traverse_(f)(_asyncForIO)

  private[effect] def runtime: IO[IORuntime] = ReadRT

  def pollers: IO[List[Any]] =
    IO.runtime.map(_.pollers)

  def uncancelable[A](body: Poll[IO] => IO[A]): IO[A] =
    Uncancelable(body, Tracing.calculateTracingEvent(body))

  private[this] val _unit: IO[Unit] = Pure(())

  /**
   * Alias for `IO.pure(())`.
   */
  def unit: IO[Unit] = _unit

  // utilities

  def stub: IO[Nothing] = {
    val e = new NotImplementedError("This IO is not implemented")
    raiseError(e)
  }

  def bothOutcome[A, B](left: IO[A], right: IO[B]): IO[(OutcomeIO[A], OutcomeIO[B])] =
    left.bothOutcome(right)

  def both[A, B](left: IO[A], right: IO[B]): IO[(A, B)] =
    asyncForIO.both(left, right)

  /**
   * Constructs an `IO` which evaluates the given `Future` and produces the result (or failure).
   *
   * Because `Future` eagerly evaluates, as well as because it memoizes, this function takes its
   * parameter as an `IO`, which could be lazily evaluated. If this laziness is appropriately
   * threaded back to the definition site of the `Future`, it ensures that the computation is
   * fully managed by `IO` and thus referentially transparent.
   *
   * Example:
   *
   * {{{
   *   // Lazy evaluation, equivalent with by-name params
   *   IO.fromFuture(IO(f))
   *
   *   // Eager evaluation, for pure futures
   *   IO.fromFuture(IO.pure(f))
   * }}}
   *
   * Roughly speaking, the following identities hold:
   *
   * {{{
   * IO.fromFuture(IO(f)).unsafeToFuture() === f // true-ish (except for memoization)
   * IO.fromFuture(IO(ioa.unsafeToFuture())) === ioa // true
   * }}}
   *
   * @see
   *   [[IO#unsafeToFuture]], [[fromFutureCancelable]]
   */
  def fromFuture[A](fut: IO[Future[A]]): IO[A] =
    asyncForIO.fromFuture(fut)

  /**
   * Like [[fromFuture]], but is cancelable via the provided finalizer.
   */
  def fromFutureCancelable[A](fut: IO[(Future[A], IO[Unit])]): IO[A] =
    asyncForIO.fromFutureCancelable(fut)

  /**
   * Run two IO tasks concurrently, and return the first to finish, either in success or error.
   * The loser of the race is canceled.
   *
   * The two tasks are executed in parallel, the winner being the first that signals a result.
   *
   * As an example see [[IO.timeout]] and [[IO.timeoutTo]]
   *
   * Also see [[racePair]] for a version that does not cancel the loser automatically on
   * successful results.
   *
   * @param left
   *   is the "left" task participating in the race
   * @param right
   *   is the "right" task participating in the race
   */
  def race[A, B](left: IO[A], right: IO[B]): IO[Either[A, B]] =
    asyncForIO.race(left, right)

  /**
   * Run two IO tasks concurrently, and returns a pair containing both the winner's successful
   * value and the loser represented as a still-unfinished task.
   *
   * On usage the user has the option of canceling the losing task, this being equivalent with
   * plain [[race]]:
   *
   * {{{
   *   val ioA: IO[A] = ???
   *   val ioB: IO[B] = ???
   *
   *   IO.racePair(ioA, ioB).flatMap {
   *     case Left((a, fiberB)) =>
   *       fiberB.cancel.as(a)
   *     case Right((fiberA, b)) =>
   *       fiberA.cancel.as(b)
   *   }
   * }}}
   *
   * See [[race]] for a simpler version that cancels the loser immediately.
   *
   * @param left
   *   is the "left" task participating in the race
   * @param right
   *   is the "right" task participating in the race
   */
  def racePair[A, B](
      left: IO[A],
      right: IO[B]): IO[Either[(OutcomeIO[A], FiberIO[B]), (FiberIO[A], OutcomeIO[B])]] =
    RacePair(left, right)

  def ref[A](a: A): IO[Ref[IO, A]] = IO(Ref.unsafe(a))

  def deferred[A]: IO[Deferred[IO, A]] = IO(new IODeferred[A])

  def bracketFull[A, B](acquire: Poll[IO] => IO[A])(use: A => IO[B])(
      release: (A, OutcomeIO[B]) => IO[Unit]): IO[B] =
    IO.uncancelable { poll =>
      acquire(poll).flatMap { a => IO.defer(poll(use(a))).guaranteeCase(release(a, _)) }
    }

  /*
   * Produce a value that is guaranteed to be unique ie
   * (IO.unique, IO.unique).mapN(_ =!= _)
   */
  def unique: IO[Unique.Token] = _asyncForIO.unique

  /**
   * Returns the given argument if `cond` is true, otherwise `IO.Unit`
   *
   * @see
   *   [[IO.unlessA]] for the inverse
   * @see
   *   [[IO.raiseWhen]] for conditionally raising an error
   */
  def whenA(cond: Boolean)(action: => IO[Unit]): IO[Unit] =
    Applicative[IO].whenA(cond)(action)

  /**
   * Returns the given argument if `cond` is false, otherwise `IO.Unit`
   *
   * @see
   *   [[IO.whenA]] for the inverse
   * @see
   *   [[IO.raiseWhen]] for conditionally raising an error
   */
  def unlessA(cond: Boolean)(action: => IO[Unit]): IO[Unit] =
    Applicative[IO].unlessA(cond)(action)

  /**
   * Returns `raiseError` when the `cond` is true, otherwise `IO.unit`
   *
   * @example
   *   {{{
   * val tooMany = 5
   * val x: Int = ???
   * IO.raiseWhen(x >= tooMany)(new IllegalArgumentException("Too many"))
   *   }}}
   */
  def raiseWhen(cond: Boolean)(e: => Throwable): IO[Unit] =
    IO.whenA(cond)(IO.raiseError(e))

  /**
   * Returns `raiseError` when `cond` is false, otherwise IO.unit
   *
   * @example
   *   {{{
   * val tooMany = 5
   * val x: Int = ???
   * IO.raiseUnless(x < tooMany)(new IllegalArgumentException("Too many"))
   *   }}}
   */
  def raiseUnless(cond: Boolean)(e: => Throwable): IO[Unit] =
    IO.unlessA(cond)(IO.raiseError(e))

  /**
   * Prints a value to the standard output using the implicit `cats.Show` instance.
   *
   * @see
   *   `cats.effect.std.Console` for more standard input, output and error operations
   *
   * @param a
   *   value to be printed to the standard output
   */
  def print[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): IO[Unit] =
    Console[IO].print(a)

  /**
   * Prints a value to the standard output followed by a new line using the implicit `cats.Show`
   * instance.
   *
   * @see
   *   `cats.effect.std.Console` for more standard input, output and error operations
   *
   * @param a
   *   value to be printed to the standard output
   */
  def println[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): IO[Unit] =
    Console[IO].println(a)

  /**
   * Lifts an `Eval` into `IO`.
   *
   * This function will preserve the evaluation semantics of any actions that are lifted into
   * the pure `IO`. Eager `Eval` instances will be converted into thunk-less `IO` (i.e. eager
   * `IO`), while lazy eval and memoized will be executed as such.
   */
  def eval[A](fa: Eval[A]): IO[A] =
    fa match {
      case Now(a) => pure(a)
      case notNow => apply(notNow.value)
    }

  /**
   * Lifts an `Option[A]` into the `IO[A]` context, raising the throwable if the option is
   * empty.
   */
  def fromOption[A](o: Option[A])(orElse: => Throwable): IO[A] =
    o match {
      case None => raiseError(orElse)
      case Some(value) => pure(value)
    }

  /**
   * Lifts an `Either[Throwable, A]` into the `IO[A]` context, raising the throwable if it
   * exists.
   */
  def fromEither[A](e: Either[Throwable, A]): IO[A] =
    e match {
      case Right(a) => pure(a)
      case Left(err) => raiseError(err)
    }

  /**
   * Lifts an `Try[A]` into the `IO[A]` context, raising the throwable if it exists.
   */
  def fromTry[A](t: Try[A]): IO[A] =
    t match {
      case Success(a) => pure(a)
      case Failure(err) => raiseError(err)
    }

  // instances

  implicit def showForIO[A: Show]: Show[IO[A]] =
    Show.show {
      case Pure(a) => s"IO(${a.show})"
      case _ => "IO(...)"
    }

  implicit def monoidForIO[A: Monoid]: Monoid[IO[A]] =
    new IOMonoid[A]

  protected class IOMonoid[A](override implicit val A: Monoid[A])
      extends IOSemigroup[A]
      with Monoid[IO[A]] {
    def empty = IO.pure(A.empty)
  }

  implicit val semigroupKForIO: SemigroupK[IO] =
    new IOSemigroupK

  protected class IOSemigroupK extends SemigroupK[IO] {
    final override def combineK[A](a: IO[A], b: IO[A]): IO[A] =
      a orElse b
  }

  implicit def alignForIO: Align[IO] = _alignForIO

  private[this] final class IOAlign extends Align[IO] {
    def align[A, B](fa: IO[A], fb: IO[B]): IO[Ior[A, B]] =
      alignWith(fa, fb)(identity)

    override def alignWith[A, B, C](fa: IO[A], fb: IO[B])(f: Ior[A, B] => C): IO[C] =
      fa.redeemWith(
        t => fb.redeemWith(_ => IO.raiseError(t), b => IO.pure(f(Ior.right(b)))),
        a => fb.redeem(_ => f(Ior.left(a)), b => f(Ior.both(a, b)))
      )

    def functor: Functor[IO] = Functor[IO]
  }

  private[this] final class IOAsync extends kernel.Async[IO] with StackSafeMonad[IO] {

    override def asyncCheckAttempt[A](
        k: (Either[Throwable, A] => Unit) => IO[Either[Option[IO[Unit]], A]]): IO[A] =
      IO.asyncCheckAttempt(k)

    override def async[A](k: (Either[Throwable, A] => Unit) => IO[Option[IO[Unit]]]): IO[A] =
      IO.async(k)

    override def async_[A](k: (Either[Throwable, A] => Unit) => Unit): IO[A] =
      IO.async_(k)

    override def as[A, B](ioa: IO[A], b: B): IO[B] =
      ioa.as(b)

    override def attempt[A](ioa: IO[A]): IO[Either[Throwable, A]] =
      ioa.attempt

    def forceR[A, B](left: IO[A])(right: IO[B]): IO[B] =
      left.forceR(right)

    def pure[A](x: A): IO[A] =
      IO.pure(x)

    override def guarantee[A](fa: IO[A], fin: IO[Unit]): IO[A] =
      fa.guarantee(fin)

    override def guaranteeCase[A](fa: IO[A])(fin: OutcomeIO[A] => IO[Unit]): IO[A] =
      fa.guaranteeCase(fin)

    override def handleError[A](fa: IO[A])(f: Throwable => A): IO[A] =
      fa.handleError(f)

    override def onError[A](fa: IO[A])(pf: PartialFunction[Throwable, IO[Unit]]): IO[A] =
      fa.onError(pf)

    override def timeout[A](fa: IO[A], duration: FiniteDuration)(
        implicit ev: TimeoutException <:< Throwable): IO[A] = {
      fa.timeout(duration)
    }

    override def timeout[A](fa: IO[A], duration: Duration)(
        implicit ev: TimeoutException <:< Throwable): IO[A] = {
      fa.timeout(duration)
    }

    def handleErrorWith[A](fa: IO[A])(f: Throwable => IO[A]): IO[A] =
      fa.handleErrorWith(f)

    def raiseError[A](e: Throwable): IO[A] =
      IO.raiseError(e)

    def cont[K, R](body: Cont[IO, K, R]): IO[R] = IO.cont(body)

    def evalOn[A](fa: IO[A], ec: ExecutionContext): IO[A] =
      fa.evalOn(ec)

    val executionContext: IO[ExecutionContext] =
      IO.executionContext

    def onCancel[A](ioa: IO[A], fin: IO[Unit]): IO[A] =
      ioa.onCancel(fin)

    override def bracketFull[A, B](acquire: Poll[IO] => IO[A])(use: A => IO[B])(
        release: (A, OutcomeIO[B]) => IO[Unit]): IO[B] =
      IO.bracketFull(acquire)(use)(release)

    val monotonic: IO[FiniteDuration] = IO.monotonic

    val realTime: IO[FiniteDuration] = IO.realTime

    def sleep(time: FiniteDuration): IO[Unit] =
      IO.sleep(time)

    def canceled: IO[Unit] =
      IO.canceled

    def cede: IO[Unit] = IO.cede

    override def productL[A, B](left: IO[A])(right: IO[B]): IO[A] =
      left.productL(right)

    override def productR[A, B](left: IO[A])(right: IO[B]): IO[B] =
      left.productR(right)

    override def replicateA[A](n: Int, fa: IO[A]): IO[List[A]] =
      fa.replicateA(n)

    def start[A](fa: IO[A]): IO[FiberIO[A]] =
      fa.start

    override def racePair[A, B](
        left: IO[A],
        right: IO[B]): IO[Either[(OutcomeIO[A], FiberIO[B]), (FiberIO[A], OutcomeIO[B])]] =
      IO.racePair(left, right)

    def uncancelable[A](body: Poll[IO] => IO[A]): IO[A] =
      IO.uncancelable(body)

    override def map[A, B](fa: IO[A])(f: A => B): IO[B] =
      fa.map(f)

    def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] =
      fa.flatMap(f)

    override def delay[A](thunk: => A): IO[A] = IO(thunk)

    /**
     * Like [[IO.delay]] but intended for thread blocking operations. `blocking` will shift the
     * execution of the blocking operation to a separate threadpool to avoid blocking on the
     * main execution context. See the thread-model documentation for more information on why
     * this is necessary. Note that the created effect will be uncancelable; if you need
     * cancelation, then you should use [[IO.interruptible]] or [[IO.interruptibleMany]].
     *
     * {{{
     * IO.blocking(scala.io.Source.fromFile("path").mkString)
     * }}}
     *
     * @param thunk
     *   The side effect which is to be suspended in `IO` and evaluated on a blocking execution
     *   context
     */
    override def blocking[A](thunk: => A): IO[A] = IO.blocking(thunk)

    /**
     * Like [[IO.blocking]] but will attempt to abort the blocking operation using thread
     * interrupts in the event of cancelation. The interrupt will be attempted only once.
     *
     * @param thunk
     *   The side effect which is to be suspended in `IO` and evaluated on a blocking execution
     *   context
     */
    override def interruptible[A](thunk: => A): IO[A] = IO.interruptible(thunk)

    /**
     * Like [[IO.blocking]] but will attempt to abort the blocking operation using thread
     * interrupts in the event of cancelation. The interrupt will be attempted repeatedly until
     * the blocking operation completes or exits.
     *
     * @note
     *   that this _really_ means what it says - it will throw exceptions in a tight loop until
     *   the offending blocking operation exits. This is extremely expensive if it happens on a
     *   hot path and the blocking operation is badly behaved and doesn't exit immediately.
     *
     * @param thunk
     *   The side effect which is to be suspended in `IO` and evaluated on a blocking execution
     *   context
     */
    override def interruptibleMany[A](thunk: => A): IO[A] = IO.interruptibleMany(thunk)

    def suspend[A](hint: Sync.Type)(thunk: => A): IO[A] =
      IO.suspend(hint)(thunk)

    override def void[A](ioa: IO[A]): IO[Unit] = ioa.void

    override def redeem[A, B](fa: IO[A])(recover: Throwable => B, f: A => B): IO[B] =
      fa.redeem(recover, f)

    override def redeemWith[A, B](
        fa: IO[A])(recover: Throwable => IO[B], bind: A => IO[B]): IO[B] =
      fa.redeemWith(recover, bind)

    override def ref[A](a: A): IO[Ref[IO, A]] = IO.ref(a)

    override def deferred[A]: IO[Deferred[IO, A]] = IO.deferred

    override def map2Eval[A, B, C](fa: IO[A], fb: Eval[IO[B]])(fn: (A, B) => C): Eval[IO[C]] =
      Eval.now(
        for {
          a <- fa
          b <- fb.value
        } yield fn(a, b)
      )

    override def syncStep[G[_], A](fa: IO[A], limit: Int)(
        implicit G: Sync[G]): G[Either[IO[A], A]] = {
      type H[+B] = G[B @uncheckedVariance]
      val H = G.asInstanceOf[Sync[H]]
      G.map(SyncStep.interpret[H, A](fa, limit)(H))(_.map(_._1))
    }
  }

  implicit def asyncForIO: kernel.Async[IO] = _asyncForIO

  private[this] val _parallelForIO: Parallel.Aux[IO, Par] =
    spawn.parallelForGenSpawn[IO, Throwable]

  implicit def parallelForIO: Parallel.Aux[IO, Par] = _parallelForIO

  implicit val consoleForIO: Console[IO] =
    Console.make

  implicit val envForIO: Env[IO] = Env.make

  // This is cached as a val to save allocations, but it uses ops from the Async
  // instance which is also cached as a val, and therefore needs to appear
  // later in the file
  private[this] val _never: IO[Nothing] = asyncForIO.never

  // implementations

  private[effect] final case class Pure[+A](value: A) extends IO[A] {
    def tag = 0
    override def toString: String = s"IO($value)"
  }

  private[effect] final case class Error(t: Throwable) extends IO[Nothing] {
    def tag = 1
  }

  private[effect] final case class Delay[+A](thunk: () => A, event: TracingEvent)
      extends IO[A] {
    def tag = 2
  }

  private[effect] case object RealTime extends IO[FiniteDuration] {
    def tag = 3
  }

  private[effect] case object Monotonic extends IO[FiniteDuration] {
    def tag = 4
  }

  private[effect] case object ReadEC extends IO[ExecutionContext] {
    def tag = 5
  }

  private[effect] final case class Map[E, +A](ioe: IO[E], f: E => A, event: TracingEvent)
      extends IO[A] {
    def tag = 6
  }

  private[effect] final case class FlatMap[E, +A](
      ioe: IO[E],
      f: E => IO[A],
      event: TracingEvent)
      extends IO[A] {
    def tag = 7
  }

  private[effect] final case class Attempt[+A](ioa: IO[A]) extends IO[Either[Throwable, A]] {
    def tag = 8
  }

  private[effect] final case class HandleErrorWith[+A](
      ioa: IO[A],
      f: Throwable => IO[A],
      event: TracingEvent)
      extends IO[A] {
    def tag = 9
  }

  private[effect] case object Canceled extends IO[Unit] {
    def tag = 10
  }

  private[effect] final case class OnCancel[+A](ioa: IO[A], fin: IO[Unit]) extends IO[A] {
    def tag = 11
  }

  private[effect] final case class Uncancelable[+A](
      body: Poll[IO] => IO[A],
      event: TracingEvent)
      extends IO[A] {
    def tag = 12
  }
  private[effect] object Uncancelable {
    // INTERNAL, it's only created by the runloop itself during the execution of `Uncancelable`
    final case class UnmaskRunLoop[+A](ioa: IO[A], id: Int, self: IOFiber[_]) extends IO[A] {
      def tag = 13
    }
  }

  // Low level construction that powers `async`
  private[effect] final case class IOCont[K, R](body: Cont[IO, K, R], event: TracingEvent)
      extends IO[R] {
    def tag = 14
  }
  private[effect] object IOCont {
    // INTERNAL, it's only created by the runloop itself during the execution of `IOCont`
    final case class Get[A](state: ContState) extends IO[A] {
      def tag = 15
    }
  }

  private[effect] case object Cede extends IO[Unit] {
    def tag = 16
  }

  private[effect] final case class Start[A](ioa: IO[A]) extends IO[FiberIO[A]] {
    def tag = 17
  }

  private[effect] final case class RacePair[A, B](ioa: IO[A], iob: IO[B])
      extends IO[Either[(OutcomeIO[A], FiberIO[B]), (FiberIO[A], OutcomeIO[B])]] {
    def tag = 18
  }

  private[effect] final case class Sleep(delay: FiniteDuration) extends IO[Unit] {
    def tag = 19
  }

  private[effect] final case class EvalOn[+A](ioa: IO[A], ec: ExecutionContext) extends IO[A] {
    def tag = 20
  }

  private[effect] final case class Blocking[+A](
      hint: Sync.Type,
      thunk: () => A,
      event: TracingEvent)
      extends IO[A] {
    def tag = 21
  }

  private[effect] final case class Local[+A](f: IOLocalState => (IOLocalState, A))
      extends IO[A] {
    def tag = 22
  }

  private[effect] case object IOTrace extends IO[Trace] {
    def tag = 23
  }

  private[effect] case object ReadRT extends IO[IORuntime] {
    def tag = 24
  }

  // INTERNAL, only created by the runloop itself as the terminal state of several operations
  private[effect] case object EndFiber extends IO[Nothing] {
    def tag = -1
  }

}

private object SyncStep {
  def interpret[G[+_], B](io: IO[B], limit: Int)(
      implicit G: Sync[G]): G[Either[IO[B], (B, Int)]] = {
    if (limit <= 0) {
      G.pure(Left(io))
    } else {
      io match {
        case IO.Pure(a) => G.pure(Right((a, limit)))
        case IO.Error(t) => G.raiseError(t)
        case IO.Delay(thunk, _) => G.delay(thunk()).map(a => Right((a, limit)))
        case IO.RealTime => G.realTime.map(a => Right((a, limit)))
        case IO.Monotonic => G.monotonic.map(a => Right((a, limit)))

        case IO.Map(ioe, f, _) =>
          interpret(ioe, limit - 1).map {
            case Left(io) => Left(io.map(f))
            case Right((a, limit)) => Right((f(a), limit))
          }

        case IO.FlatMap(ioe, f, _) =>
          interpret(ioe, limit - 1).flatMap {
            case Left(io) => G.pure(Left(io.flatMap(f)))
            case Right((a, limit)) => interpret(f(a), limit - 1)
          }

        case IO.Attempt(ioe) =>
          interpret(ioe, limit - 1)
            .map {
              case Left(io) => Left(io.attempt)
              case Right((a, limit)) => Right((a.asRight[Throwable], limit))
            }
            .handleError(t => (t.asLeft, limit - 1).asRight)

        case IO.HandleErrorWith(ioe, f, _) =>
          interpret(ioe, limit - 1)
            .map {
              case Left(io) => Left(io.handleErrorWith(f))
              case r @ Right(_) => r
            }
            .handleErrorWith(t => interpret(f(t), limit - 1))

        case IO.Uncancelable(body, _) if G.rootCancelScope == CancelScope.Uncancelable =>
          val ioa = body(new Poll[IO] {
            def apply[C](ioc: IO[C]): IO[C] = ioc
          })
          interpret(ioa, limit)

        case IO.OnCancel(ioa, _) if G.rootCancelScope == CancelScope.Uncancelable =>
          interpret(ioa, limit)

        case _ => G.pure(Left(io))
      }
    }
  }

}
