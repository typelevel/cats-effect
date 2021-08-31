/*
 * Copyright 2020-2021 Typelevel
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
  Eval,
  Functor,
  Id,
  Monad,
  Monoid,
  Now,
  Parallel,
  Semigroup,
  SemigroupK,
  Show,
  StackSafeMonad,
  Traverse
}
import cats.data.Ior
import cats.syntax.all._
import cats.effect.instances.spawn
import cats.effect.std.Console
import cats.effect.tracing.{Tracing, TracingEvent}
import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.{
  CancellationException,
  ExecutionContext,
  Future,
  Promise,
  TimeoutException
}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * A pure abstraction representing the intention to perform a
 * side effect, where the result of that side effect may be obtained
 * synchronously (via return) or asynchronously (via callback).
 *
 * `IO` values are pure, immutable values and thus preserve
 * referential transparency, being usable in functional programming.
 * An `IO` is a data structure that represents just a description
 * of a side effectful computation.
 *
 * `IO` can describe synchronous or asynchronous computations that:
 *
 *  1. on evaluation yield exactly one result
 *  2. can end in either success or failure and in case of failure
 *     `flatMap` chains get short-circuited (`IO` implementing
 *     the algebra of `MonadError`)
 *  3. can be canceled, but note this capability relies on the
 *     user to provide cancelation logic
 *
 * Effects described via this abstraction are not evaluated until
 * the "end of the world", which is to say, when one of the "unsafe"
 * methods are used. Effectful results are not memoized, meaning that
 * memory overhead is minimal (and no leaks), and also that a single
 * effect may be run multiple times in a referentially-transparent
 * manner. For example:
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
 * The above will print "hey!" twice, as the effect will be re-run
 * each time it is sequenced in the monadic chain.
 *
 * `IO` is trampolined in its `flatMap` evaluation. This means that
 * you can safely call `flatMap` in a recursive function of arbitrary
 * depth, without fear of blowing the stack.
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
 * @see [[IOApp]] for the preferred way of executing whole programs wrapped in `IO`
 */
sealed abstract class IO[+A] private () extends IOPlatform[A] {

  private[effect] def tag: Byte

  /**
   * Like [[*>]], but keeps the result of the source.
   *
   * For a similar method that also runs the parameter in case of failure or interruption, see [[guarantee]].
   */
  def <*[B](that: IO[B]): IO[A] =
    productL(that)

  /**
   * Runs the current IO, then runs the parameter, keeping its result.
   * The result of the first action is ignored. If the source fails,
   * the other action won't run. Not suitable for use when the parameter
   * is a recursive reference to the current expression.
   *
   * @see [[>>]] for the recursion-safe, lazily evaluated alternative
   */
  def *>[B](that: IO[B]): IO[B] =
    productR(that)

  /**
   * Runs the current IO, then runs the parameter, keeping its result.
   * The result of the first action is ignored.
   * If the source fails, the other action won't run. Evaluation of the
   * parameter is done lazily, making this suitable for recursion.
   *
   * @see [*>] for the strictly evaluated alternative
   */
  def >>[B](that: => IO[B]): IO[B] =
    flatMap(_ => that)

  def !>[B](that: IO[B]): IO[B] =
    forceR(that)

  /**
   * Runs this IO and the parameter in parallel.
   *
   * Failure in either of the IOs will cancel the other one.
   * If the whole computation is canceled, both actions are also canceled.
   */
  def &>[B](that: IO[B]): IO[B] =
    both(that).map { case (_, b) => b }

  /**
   * Like [[&>]], but keeps the result of the source
   */
  def <&[B](that: IO[B]): IO[A] =
    both(that).map { case (a, _) => a }

  /**
   * Replaces the result of this IO with the given value.
   */
  def as[B](b: B): IO[B] =
    map(_ => b)

  /**
   * Materializes any sequenced exceptions into value space, where
   * they may be handled.
   *
   * This is analogous to the `catch` clause in `try`/`catch`, being
   * the inverse of `IO.raiseError`. Thus:
   *
   * {{{
   * IO.raiseError(ex).attempt.unsafeRunAsync === Left(ex)
   * }}}
   *
   * @see [[IO.raiseError]]
   */
  def attempt: IO[Either[Throwable, A]] =
    IO.Attempt(this)

  /**
   * Replaces failures in this IO with an empty Option.
   */
  def option: IO[Option[A]] =
    redeem(_ => None, Some(_))

  /**
   * Runs the current and given IO in parallel, producing the pair of
   * the outcomes. Both outcomes are produced, regardless of whether
   * they complete successfully.
   *
   * @see [[both]] for the version which embeds the outcomes to produce a pair
   *               of the results
   * @see [[raceOutcome]] for the version which produces the outcome of the
   *                      winner and cancels the loser of the race
   */
  def bothOutcome[B](that: IO[B]): IO[(OutcomeIO[A @uncheckedVariance], OutcomeIO[B])] =
    IO.uncancelable { poll =>
      racePair(that).flatMap {
        case Left((oc, f)) => poll(f.join).onCancel(f.cancel).map((oc, _))
        case Right((f, oc)) => poll(f.join).onCancel(f.cancel).map((_, oc))
      }
    }

  /**
   * Runs the current and given IO in parallel, producing the pair of
   * the results. If either fails with an error, the result of the whole
   * will be that error and the other will be canceled.
   *
   * @see [[bothOutcome]] for the version which produces the outcome of both
   *                      effects executed in parallel
   * @see [[race]] for the version which produces the result of the winner and
   *               cancels the loser of the race
   */
  def both[B](that: IO[B]): IO[(A, B)] =
    IO.both(this, that)

  /**
   * Returns an `IO` action that treats the source task as the
   * acquisition of a resource, which is then exploited by the `use`
   * function and then `released`.
   *
   * The `bracket` operation is the equivalent of the
   * `try {} catch {} finally {}` statements from mainstream languages.
   *
   * The `bracket` operation installs the necessary exception handler
   * to release the resource in the event of an exception being raised
   * during the computation, or in case of cancelation.
   *
   * If an exception is raised, then `bracket` will re-raise the
   * exception ''after'' performing the `release`. If the resulting
   * task gets canceled, then `bracket` will still perform the
   * `release`, but the yielded task will be non-terminating
   * (equivalent with [[IO.never]]).
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
   * Note that in case of cancelation the underlying implementation
   * cannot guarantee that the computation described by `use` doesn't
   * end up executed concurrently with the computation from
   * `release`. In the example above that ugly Java loop might end up
   * reading from a `BufferedReader` that is already closed due to the
   * task being canceled, thus triggering an error in the background
   * with nowhere to get signaled.
   *
   * In this particular example, given that we are just reading from a
   * file, it doesn't matter. But in other cases it might matter, as
   * concurrency on top of the JVM when dealing with I/O might lead to
   * corrupted data.
   *
   * For those cases you might want to do synchronization (e.g. usage
   * of locks and semaphores) and you might want to use [[bracketCase]],
   * the version that allows you to differentiate between normal
   * termination and cancelation.
   *
   * '''NOTE on error handling''': in case both the `release`
   * function and the `use` function throws, the error raised by `release`
   * gets signaled.
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
   * In this case the resulting `IO` will raise error `foo`, while the
   * `bar` error gets reported on a side-channel. This is consistent
   * with the behavior of Java's "Try with resources" except that no
   * involved exceptions are mutated (i.e., in contrast to Java, `bar`
   * isn't added as a suppressed exception to `foo`).
   *
   * @see [[bracketCase]]
   *
   * @param use is a function that evaluates the resource yielded by
   *        the source, yielding a result that will get generated by
   *        the task returned by this `bracket` function
   *
   * @param release is a function that gets called after `use`
   *        terminates, either normally or in error, or if it gets
   *        canceled, receiving as input the resource that needs to
   *        be released
   */
  def bracket[B](use: A => IO[B])(release: A => IO[Unit]): IO[B] =
    bracketCase(use)((a, _) => release(a))

  /**
   * Returns a new `IO` task that treats the source task as the
   * acquisition of a resource, which is then exploited by the `use`
   * function and then `released`, with the possibility of
   * distinguishing between normal termination and cancelation, such
   * that an appropriate release of resources can be executed.
   *
   * The `bracketCase` operation is the equivalent of
   * `try {} catch {} finally {}` statements from mainstream languages
   * when used for the acquisition and release of resources.
   *
   * The `bracketCase` operation installs the necessary exception handler
   * to release the resource in the event of an exception being raised
   * during the computation, or in case of cancelation.
   *
   * In comparison with the simpler [[bracket]] version, this one
   * allows the caller to differentiate between normal termination,
   * termination in error and cancelation via an [[Outcome]]
   * parameter.
   *
   * @see [[bracket]]
   *
   * @param use is a function that evaluates the resource yielded by
   *        the source, yielding a result that will get generated by
   *        this function on evaluation
   *
   * @param release is a function that gets called after `use`
   *        terminates, either normally or in error, or if it gets
   *        canceled, receiving as input the resource that needs
   *        release, along with the result of `use`
   *        (cancelation, error or successful result)
   */
  def bracketCase[B](use: A => IO[B])(release: (A, OutcomeIO[B]) => IO[Unit]): IO[B] =
    IO.bracketFull(_ => this)(use)(release)

  /**
   * Shifts the execution of the current IO to the specified `ExecutionContext`.
   * All stages of the execution will default to the pool in question, and any
   * asynchronous callbacks will shift back to the pool upon completion. Any nested
   * use of `evalOn` will override the specified pool. Once the execution fully
   * completes, default control will be shifted back to the enclosing (inherited) pool.
   *
   * @see [[IO.executionContext]] for obtaining the `ExecutionContext` on which
   *                              the current `IO` is being executed
   */
  def evalOn(ec: ExecutionContext): IO[A] = IO.EvalOn(this, ec)

  def startOn(ec: ExecutionContext): IO[FiberIO[A @uncheckedVariance]] = start.evalOn(ec)

  def backgroundOn(ec: ExecutionContext): ResourceIO[IO[OutcomeIO[A @uncheckedVariance]]] =
    Resource.make(startOn(ec))(_.cancel).map(_.join)

  def forceR[B](that: IO[B]): IO[B] =
    handleError(_ => ()).productR(that)

  /**
   * Monadic bind on `IO`, used for sequentially composing two `IO`
   * actions, where the value produced by the first `IO` is passed as
   * input to a function producing the second `IO` action.
   *
   * Due to this operation's signature, `flatMap` forces a data
   * dependency between two `IO` actions, thus ensuring sequencing
   * (e.g. one action to be executed before another one).
   *
   * Any exceptions thrown within the function will be caught and
   * sequenced into the `IO`, because due to the nature of
   * asynchronous processes, without catching and handling exceptions,
   * failures would be completely silent and `IO` references would
   * never terminate on evaluation.
   */
  def flatMap[B](f: A => IO[B]): IO[B] =
    IO.FlatMap(this, f, Tracing.calculateTracingEvent(f.getClass))

  def flatten[B](implicit ev: A <:< IO[B]): IO[B] = flatMap(ev)

  def flatTap[B](f: A => IO[B]): IO[A] = flatMap(a => f(a).as(a))

  /**
   * Executes the given `finalizer` when the source is finished,
   * either in success or in error, or if canceled.
   *
   * This variant of [[guaranteeCase]] evaluates the given `finalizer`
   * regardless of how the source gets terminated:
   *
   *  - normal completion
   *  - completion in error
   *  - cancelation
   *
   * This equivalence always holds:
   *
   * {{{
   *   io.guarantee(f) <-> IO.unit.bracket(_ => io)(_ => f)
   * }}}
   *
   * @see [[guaranteeCase]] for the version that can discriminate
   *      between termination conditions
   */
  def guarantee(finalizer: IO[Unit]): IO[A] =
    // this is a little faster than the default implementation, which helps Resource
    IO uncancelable { poll =>
      val handled = finalizer handleErrorWith { t =>
        IO.executionContext.flatMap(ec => IO(ec.reportFailure(t)))
      }

      poll(this).onCancel(finalizer).onError(_ => handled).flatTap(_ => finalizer)
    }

  /**
   * Executes the given `finalizer` when the source is finished,
   * either in success or in error, or if canceled, allowing
   * for differentiating between exit conditions.
   *
   * This variant of [[guarantee]] injects an [[Outcome]] in
   * the provided function, allowing one to make a difference
   * between:
   *
   *  - normal completion
   *  - completion in error
   *  - cancelation
   *
   * This equivalence always holds:
   *
   * {{{
   *   io.guaranteeCase(f) <-> IO.unit.bracketCase(_ => io)((_, e) => f(e))
   * }}}
   *
   * @see [[guarantee]] for the simpler version
   */
  def guaranteeCase(finalizer: OutcomeIO[A @uncheckedVariance] => IO[Unit]): IO[A] =
    IO.uncancelable { poll =>
      val finalized = poll(this).onCancel(finalizer(Outcome.canceled))
      val handled = finalized.onError { e =>
        finalizer(Outcome.errored(e)).handleErrorWith { t =>
          IO.executionContext.flatMap(ec => IO(ec.reportFailure(t)))
        }
      }
      handled.flatTap(a => finalizer(Outcome.succeeded(IO.pure(a))))
    }

  def handleError[B >: A](f: Throwable => B): IO[B] =
    handleErrorWith[B](t => IO.pure(f(t)))

  /**
   * Handle any error, potentially recovering from it, by mapping it to another
   * `IO` value.
   *
   * Implements `ApplicativeError.handleErrorWith`.
   */
  def handleErrorWith[B >: A](f: Throwable => IO[B]): IO[B] =
    IO.HandleErrorWith(this, f, Tracing.calculateTracingEvent(f.getClass))

  def ifM[B](ifTrue: => IO[B], ifFalse: => IO[B])(implicit ev: A <:< Boolean): IO[B] =
    flatMap(a => if (ev(a)) ifTrue else ifFalse)

  /**
   * Functor map on `IO`. Given a mapping function, it transforms the
   * value produced by the source, while keeping the `IO` context.
   *
   * Any exceptions thrown within the function will be caught and
   * sequenced into the `IO`. Due to the nature of
   * asynchronous processes, without catching and handling exceptions,
   * failures would be completely silent and `IO` references would
   * never terminate on evaluation.
   */
  def map[B](f: A => B): IO[B] = IO.Map(this, f, Tracing.calculateTracingEvent(f.getClass))

  def onCancel(fin: IO[Unit]): IO[A] =
    IO.OnCancel(this, fin)

  def onError(f: Throwable => IO[Unit]): IO[A] =
    handleErrorWith(t => f(t).attempt *> IO.raiseError(t))

  def race[B](that: IO[B]): IO[Either[A, B]] =
    IO.race(this, that)

  def raceOutcome[B](that: IO[B]): IO[Either[OutcomeIO[A @uncheckedVariance], OutcomeIO[B]]] =
    IO.uncancelable { _ =>
      racePair(that).flatMap {
        case Left((oc, f)) => f.cancel.as(Left(oc))
        case Right((f, oc)) => f.cancel.as(Right(oc))
      }
    }

  def racePair[B](that: IO[B]): IO[Either[
    (OutcomeIO[A @uncheckedVariance], FiberIO[B]),
    (FiberIO[A @uncheckedVariance], OutcomeIO[B])]] =
    IO.racePair(this, that)

  /**
   * Returns a new value that transforms the result of the source,
   * given the `recover` or `map` functions, which get executed depending
   * on whether the result ends in error or if it is successful.
   *
   * This is an optimization on usage of [[attempt]] and [[map]],
   * this equivalence being true:
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
   * @param recover is a function used for error recover in case the
   *        source ends in error
   * @param map is a function used for mapping the result of the source
   *        in case it ends in success
   */
  def redeem[B](recover: Throwable => B, map: A => B): IO[B] =
    attempt.map(_.fold(recover, map))

  /**
   * Returns a new value that transforms the result of the source,
   * given the `recover` or `bind` functions, which get executed depending
   * on whether the result ends in error or if it is successful.
   *
   * This is an optimization on usage of [[attempt]] and [[flatMap]],
   * this equivalence being available:
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
   * @param recover is the function that gets called to recover the source
   *        in case of error
   * @param bind is the function that gets to transform the source
   *        in case of success
   */
  def redeemWith[B](recover: Throwable => IO[B], bind: A => IO[B]): IO[B] =
    attempt.flatMap(_.fold(recover, bind))

  /**
   * Returns an IO that will delay the execution of the source by the given duration.
   */
  def delayBy(duration: FiniteDuration): IO[A] =
    IO.sleep(duration) *> this

  /**
   * Returns an IO that either completes with the result of the source within
   * the specified time `duration` or otherwise raises a `TimeoutException`.
   *
   * The source is canceled in the event that it takes longer than
   * the specified time duration to complete.
   *
   * @param duration is the time span for which we wait for the source to
   *        complete; in the event that the specified time has passed without
   *        the source completing, a `TimeoutException` is raised
   */
  def timeout[A2 >: A](duration: FiniteDuration): IO[A2] =
    timeoutTo(duration, IO.raiseError(new TimeoutException(duration.toString)))

  /**
   * Returns an IO that either completes with the result of the source within
   * the specified time `duration` or otherwise evaluates the `fallback`.
   *
   * The source is canceled in the event that it takes longer than
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
  def timeoutTo[A2 >: A](duration: FiniteDuration, fallback: IO[A2]): IO[A2] =
    race(IO.sleep(duration)).flatMap {
      case Right(_) => fallback
      case Left(value) => IO.pure(value)
    }

  def timed: IO[(FiniteDuration, A)] =
    Clock[IO].timed(this)

  def product[B](that: IO[B]): IO[(A, B)] =
    flatMap(a => that.map(b => (a, b)))

  def productL[B](that: IO[B]): IO[A] =
    flatMap(a => that.as(a))

  def productR[B](that: IO[B]): IO[B] =
    flatMap(_ => that)

  /**
   * Start execution of the source suspended in the `IO` context.
   *
   * This can be used for non-deterministic / concurrent execution.
   * The following code is more or less equivalent with `parMap2`
   * (minus the behavior on error handling and cancelation):
   *
   * {{{
   *   def par2[A, B](ioa: IO[A], iob: IO[B]): IO[(A, B)] =
   *     for {
   *       fa <- ioa.start
   *       fb <- iob.start
   *        a <- fa.join
   *        b <- fb.join
   *     } yield (a, b)
   * }}}
   *
   * Note in such a case usage of `parMapN` (via `cats.Parallel`) is
   * still recommended because of behavior on error and cancelation —
   * consider in the example above what would happen if the first task
   * finishes in error. In that case the second task doesn't get canceled,
   * which creates a potential memory leak.
   *
   * Also see [[background]] for a safer alternative.
   */
  def start: IO[FiberIO[A @uncheckedVariance]] =
    IO.Start(this)

  /**
   * Returns a resource that will start execution of this IO in the background.
   *
   * In case the resource is closed while this IO is still running (e.g. due to a failure in `use`),
   * the background action will be canceled.
   *
   * @see [[cats.effect.kernel.GenSpawn#background]] for the generic version.
   */
  def background: ResourceIO[IO[OutcomeIO[A @uncheckedVariance]]] =
    Spawn[IO].background(this)

  def memoize: IO[IO[A]] =
    Concurrent[IO].memoize(this)

  /**
   * Makes the source `IO` uninterruptible such that a [[cats.effect.kernel.Fiber#cancel]]
   * signal is ignored until completion.
   *
   * @see [[IO.uncancelable]] for constructing uncancelable `IO` values with
   *                          user-configurable cancelable regions
   */
  def uncancelable: IO[A] =
    IO.uncancelable(_ => this)

  /**
   * Ignores the result of this IO.
   */
  def void: IO[Unit] =
    map(_ => ())

  /**
   * Converts the source `IO` into any `F` type that implements
   * the [[LiftIO]] type class.
   */
  def to[F[_]](implicit F: LiftIO[F]): F[A @uncheckedVariance] =
    F.liftIO(this)

  override def toString: String = "IO(...)"

  // unsafe stuff

  /**
   * Passes the result of the encapsulated effects to the given
   * callback by running them as impure side effects.
   *
   * Any exceptions raised within the effect will be passed to the
   * callback in the `Either`.  The callback will be invoked at most
   * *once*.  Note that it is very possible to construct an IO which
   * never returns while still never blocking a thread, and attempting
   * to evaluate that IO with this method will result in a situation
   * where the callback is *never* invoked.
   *
   * As the name says, this is an UNSAFE function as it is impure and
   * performs side effects.  You should ideally only call this
   * function ''once'', at the very end of your program.
   */
  def unsafeRunAsync(cb: Either[Throwable, A] => Unit)(
      implicit runtime: unsafe.IORuntime): Unit = {
    unsafeRunFiber(
      cb(Left(new CancellationException("The fiber was canceled"))),
      t => cb(Left(t)),
      a => cb(Right(a)))
    ()
  }

  def unsafeRunAsyncOutcome(cb: Outcome[Id, Throwable, A @uncheckedVariance] => Unit)(
      implicit runtime: unsafe.IORuntime): Unit = {
    unsafeRunFiber(
      cb(Outcome.canceled),
      t => cb(Outcome.errored(t)),
      a => cb(Outcome.succeeded(a: Id[A])))
    ()
  }

  /**
   * Triggers the evaluation of the source and any suspended side
   * effects therein, but ignores the result.
   *
   * This operation is similar to [[unsafeRunAsync]], in that the
   * evaluation can happen asynchronously, except no callback is required
   * and therefore the result is ignored.
   *
   * Note that errors still get logged (via IO's internal logger),
   * because errors being thrown should never be totally silent.
   */
  def unsafeRunAndForget()(implicit runtime: unsafe.IORuntime): Unit =
    unsafeRunAsync(_ => ())

  /**
   * Evaluates the effect and produces the result in a `Future`.
   *
   * This is similar to `unsafeRunAsync` in that it evaluates the `IO`
   * as a side effect in a non-blocking fashion, but uses a `Future`
   * rather than an explicit callback.  This function should really
   * only be used if interoperating with code which uses Scala futures.
   *
   * @see [[IO.fromFuture]]
   */
  def unsafeToFuture()(implicit runtime: unsafe.IORuntime): Future[A] =
    unsafeToFutureCancelable()._1

  /**
   * Evaluates the effect and produces the result in a `Future`, along with a
   * cancelation token that can be used to cancel the original effect.
   *
   * This is similar to `unsafeRunAsync` in that it evaluates the `IO`
   * as a side effect in a non-blocking fashion, but uses a `Future`
   * rather than an explicit callback.  This function should really
   * only be used if interoperating with code which uses Scala futures.
   *
   * @see [[IO.fromFuture]]
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
   * Evaluates the effect, returning a cancelation token that can be used to
   * cancel it.
   *
   * This is similar to `unsafeRunAsync` in that it evaluates the `IO`
   * as a side effect in a non-blocking fashion, but uses a `Future`
   * rather than an explicit callback.  This function should really
   * only be used if interoperating with code which uses Scala futures.
   *
   * @see [[IO.fromFuture]]
   */
  def unsafeRunCancelable()(implicit runtime: unsafe.IORuntime): () => Future[Unit] =
    unsafeToFutureCancelable()._2

  private[effect] def unsafeRunFiber(
      canceled: => Unit,
      failure: Throwable => Unit,
      success: A => Unit)(implicit runtime: unsafe.IORuntime): IOFiber[A @uncheckedVariance] = {

    val fiber = new IOFiber[A](
      0,
      Map(),
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

    runtime.fiberErrorCbs.put(failure)
    runtime.compute.execute(fiber)
    fiber
  }

  /**
   * Translates this `IO[A]` into a `SyncIO` value which, when evaluated, runs
   * the original `IO` to its completion, or until the first asynchronous,
   * boundary, whichever is encountered first.
   */
  def syncStep: SyncIO[Either[IO[A], A]] = {
    def interpret[B](io: IO[B]): SyncIO[Either[IO[B], B]] =
      io match {
        case IO.Pure(a) => SyncIO.pure(Right(a))
        case IO.Error(t) => SyncIO.raiseError(t)
        case IO.Delay(thunk, _) => SyncIO.delay(thunk()).map(Right(_))
        case IO.RealTime => SyncIO.realTime.map(Right(_))
        case IO.Monotonic => SyncIO.monotonic.map(Right(_))

        case IO.Map(ioe, f, _) =>
          interpret(ioe).map {
            case Left(_) => Left(io)
            case Right(a) => Right(f(a))
          }

        case IO.FlatMap(ioe, f, _) =>
          interpret(ioe).flatMap {
            case Left(_) => SyncIO.pure(Left(io))
            case Right(a) => interpret(f(a))
          }

        case IO.Attempt(ioe) =>
          interpret(ioe)
            .map {
              case Left(_) => Left(io)
              case Right(a) => Right(a.asRight[Throwable])
            }
            .handleError(t => Right(t.asLeft[IO[B]]))

        case IO.HandleErrorWith(ioe, f, _) =>
          interpret(ioe)
            .map {
              case Left(_) => Left(io)
              case Right(a) => Right(a)
            }
            .handleErrorWith(t => interpret(f(t)))

        case _ => SyncIO.pure(Left(io))
      }

    interpret(this)
  }

  /**
   * Evaluates the current `IO` in an infinite loop, terminating only on
   * error or cancelation.
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

object IO extends IOCompanionPlatform with IOLowPriorityImplicits {

  /**
   * Newtype encoding for an `IO` datatype that has a `cats.Applicative`
   * capable of doing parallel processing in `ap` and `map2`, needed
   * for implementing `cats.Parallel`.
   *
   * Helpers are provided for converting back and forth in `Par.apply`
   * for wrapping any `IO` value and `Par.unwrap` for unwrapping.
   *
   * The encoding is based on the "newtypes" project by
   * Alexander Konovalov, chosen because it's devoid of boxing issues and
   * a good choice until opaque types will land in Scala.
   */
  type Par[A] = ParallelF[IO, A]

  // constructors

  /**
   * Suspends a synchronous side effect in `IO`.
   *
   * Alias for `IO.delay(body)`.
   */
  def apply[A](thunk: => A): IO[A] = {
    val fn = Thunk.asFunction0(thunk)
    Delay(fn, Tracing.calculateTracingEvent(fn.getClass))
  }

  /**
   * Suspends a synchronous side effect in `IO`.
   *
   * Any exceptions thrown by the effect will be caught and sequenced
   * into the `IO`.
   */
  def delay[A](thunk: => A): IO[A] = apply(thunk)

  /**
   * Suspends a synchronous side effect which produces an `IO` in `IO`.
   *
   * This is useful for trampolining (i.e. when the side effect is
   * conceptually the allocation of a stack frame).  Any exceptions
   * thrown by the side effect will be caught and sequenced into the
   * `IO`.
   */
  def defer[A](thunk: => IO[A]): IO[A] =
    delay(thunk).flatten

  def async[A](k: (Either[Throwable, A] => Unit) => IO[Option[IO[Unit]]]): IO[A] = {
    val body = new Cont[IO, A, A] {
      def apply[G[_]](implicit G: MonadCancel[G, Throwable]) = { (resume, get, lift) =>
        G.uncancelable { poll =>
          lift(k(resume)) flatMap {
            case Some(fin) => G.onCancel(poll(get), lift(fin))
            case None => poll(get)
          }
        }
      }
    }

    IOCont(body, Tracing.calculateTracingEvent(k.getClass))
  }

  /**
   * Suspends an asynchronous side effect in `IO`.
   *
   * The given function will be invoked during evaluation of the `IO`
   * to "schedule" the asynchronous callback, where the callback is
   * the parameter passed to that function.  Only the ''first''
   * invocation of the callback will be effective!  All subsequent
   * invocations will be silently dropped.
   *
   * As a quick example, you can use this function to perform a
   * parallel computation given an `ExecutorService`:
   *
   * {{{
   * def fork[A](body: => A, exc: ExecutorService): IO[A] =
   *   IO async_ { cb =>
   *     exc.execute(new Runnable {
   *       def run() =
   *         try cb(Right(body)) catch { case NonFatal(t) => cb(Left(t)) }
   *     })
   *   }
   * }}}
   *
   * The `fork` function will do exactly what it sounds like: take a
   * thunk and an `ExecutorService` and run that thunk on the thread
   * pool.  Or rather, it will produce an `IO` which will do those
   * things when run; it does *not* schedule the thunk until the
   * resulting `IO` is run!  Note that there is no thread blocking in
   * this implementation; the resulting `IO` encapsulates the callback
   * in a pure and monadic fashion without using threads.
   *
   * This function can be thought of as a safer, lexically-constrained
   * version of `Promise`, where `IO` is like a safer, lazy version of
   * `Future`.
   *
   * @see [[async]]
   */
  def async_[A](k: (Either[Throwable, A] => Unit) => Unit): IO[A] = {
    val body = new Cont[IO, A, A] {
      def apply[G[_]](implicit G: MonadCancel[G, Throwable]) = { (resume, get, lift) =>
        G.uncancelable { poll => lift(IO.delay(k(resume))).flatMap(_ => poll(get)) }
      }
    }

    IOCont(body, Tracing.calculateTracingEvent(k.getClass))
  }

  def canceled: IO[Unit] = Canceled

  def cede: IO[Unit] = Cede

  /**
   * This is a low-level API which is meant for implementors,
   * please use `background`, `start`, `async`, or `Deferred` instead,
   * depending on the use case
   */
  def cont[K, R](body: Cont[IO, K, R]): IO[R] =
    IOCont[K, R](body, Tracing.calculateTracingEvent(body.getClass))

  def executionContext: IO[ExecutionContext] = ReadEC

  def monotonic: IO[FiniteDuration] = Monotonic

  /**
   * A non-terminating `IO`, alias for `async(_ => ())`.
   */
  def never[A]: IO[A] = _never

  /**
   * An IO that contains an empty Option.
   *
   * @see [[some]] for the non-empty Option variant
   */
  def none[A]: IO[Option[A]] = pure(None)

  /**
   * An IO that contains some Option of the given value.
   *
   * @see [[none]] for the empty Option variant
   */
  def some[A](a: A): IO[Option[A]] = pure(Some(a))

  /**
   * Like `Parallel.parTraverse`, but limits the degree of parallelism.
   */
  def parTraverseN[T[_]: Traverse, A, B](n: Int)(ta: T[A])(f: A => IO[B]): IO[T[B]] =
    _asyncForIO.parTraverseN(n)(ta)(f)

  /**
   * Like `Parallel.parSequence`, but limits the degree of parallelism.
   */
  def parSequenceN[T[_]: Traverse, A](n: Int)(tma: T[IO[A]]): IO[T[A]] =
    _asyncForIO.parSequenceN(n)(tma)

  /**
   * Lifts a pure value into `IO`.
   *
   * This should ''only'' be used if the value in question has
   * "already" been computed!  In other words, something like
   * `IO.pure(readLine)` is most definitely not the right thing to do!
   * However, `IO.pure(42)` is correct and will be more efficient
   * (when evaluated) than `IO(42)`, due to avoiding the allocation of
   * extra thunks.
   */
  def pure[A](value: A): IO[A] = Pure(value)

  /**
   * Constructs an `IO` which sequences the specified exception.
   *
   * If this `IO` is run using `unsafeRunSync` or `unsafeRunTimed`,
   * the exception will be thrown.  This exception can be "caught" (or
   * rather, materialized into value-space) using the `attempt`
   * method.
   *
   * @see [[IO#attempt]]
   */
  def raiseError[A](t: Throwable): IO[A] = Error(t)

  def realTime: IO[FiniteDuration] = RealTime

  /**
   * Creates an asynchronous task that on evaluation sleeps for the
   * specified duration, emitting a notification on completion.
   *
   * This is the pure, non-blocking equivalent to:
   *
   *  - `Thread.sleep` (JVM)
   *  - `ScheduledExecutorService.schedule` (JVM)
   *  - `setTimeout` (JavaScript)
   *
   * You can combine it with `flatMap` to create delayed tasks:
   *
   * {{{
   *   val timeout = IO.sleep(10.seconds).flatMap { _ =>
   *     IO.raiseError(new TimeoutException)
   *   }
   * }}}
   *
   * The created task is cancelable and so it can be used safely in race
   * conditions without resource leakage.
   *
   * @param duration is the time span to wait before emitting the tick
   *
   * @return a new asynchronous and cancelable `IO` that will sleep for
   *         the specified duration and then finally emit a tick
   */
  def sleep(delay: FiniteDuration): IO[Unit] =
    Sleep(delay)

  def trace: IO[Trace] =
    IOTrace

  def uncancelable[A](body: Poll[IO] => IO[A]): IO[A] =
    Uncancelable(body, Tracing.calculateTracingEvent(body.getClass))

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
   * Constructs an `IO` which evaluates the given `Future` and
   * produces the result (or failure).
   *
   * Because `Future` eagerly evaluates, as well as because it
   * memoizes, this function takes its parameter as an `IO`,
   * which could be lazily evaluated.  If this laziness is
   * appropriately threaded back to the definition site of the
   * `Future`, it ensures that the computation is fully managed by
   * `IO` and thus referentially transparent.
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
   * @see [[IO#unsafeToFuture]]
   */
  def fromFuture[A](fut: IO[Future[A]]): IO[A] =
    asyncForIO.fromFuture(fut)

  /**
   * Run two IO tasks concurrently, and return the first to
   * finish, either in success or error. The loser of the race is
   * canceled.
   *
   * The two tasks are executed in parallel, the winner being the
   * first that signals a result.
   *
   * As an example see [[IO.timeout]] and [[IO.timeoutTo]]
   *
   * Also see [[racePair]] for a version that does not cancel
   * the loser automatically on successful results.
   *
   * @param lh is the "left" task participating in the race
   * @param rh is the "right" task participating in the race
   */
  def race[A, B](left: IO[A], right: IO[B]): IO[Either[A, B]] =
    asyncForIO.race(left, right)

  /**
   * Run two IO tasks concurrently, and returns a pair
   * containing both the winner's successful value and the loser
   * represented as a still-unfinished task.
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
   *   IO.racePair(ioA, ioB).flatMap {
   *     case Left((a, fiberB)) =>
   *       fiberB.cancel.map(_ => a)
   *     case Right((fiberA, b)) =>
   *       fiberA.cancel.map(_ => b)
   *   }
   * }}}
   *
   * See [[race]] for a simpler version that cancels the loser
   * immediately.
   *
   * @param lh is the "left" task participating in the race
   * @param rh is the "right" task participating in the race
   */
  def racePair[A, B](
      left: IO[A],
      right: IO[B]): IO[Either[(OutcomeIO[A], FiberIO[B]), (FiberIO[A], OutcomeIO[B])]] =
    RacePair(left, right)

  def ref[A](a: A): IO[Ref[IO, A]] = IO(Ref.unsafe(a))

  def deferred[A]: IO[Deferred[IO, A]] = IO(Deferred.unsafe)

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
   * @see [[IO.unlessA]] for the inverse
   * @see [[IO.raiseWhen]] for conditionally raising an error
   */
  def whenA(cond: Boolean)(action: => IO[Unit]): IO[Unit] =
    Applicative[IO].whenA(cond)(action)

  /**
   * Returns the given argument if `cond` is false, otherwise `IO.Unit`
   *
   * @see [[IO.whenA]] for the inverse
   * @see [[IO.raiseWhen]] for conditionally raising an error
   */
  def unlessA(cond: Boolean)(action: => IO[Unit]): IO[Unit] =
    Applicative[IO].unlessA(cond)(action)

  /**
   * Returns `raiseError` when the `cond` is true, otherwise `IO.unit`
   *
   * @example {{{
   * val tooMany = 5
   * val x: Int = ???
   * IO.raiseWhen(x >= tooMany)(new IllegalArgumentException("Too many"))
   * }}}
   */
  def raiseWhen(cond: Boolean)(e: => Throwable): IO[Unit] =
    IO.whenA(cond)(IO.raiseError(e))

  /**
   * Returns `raiseError` when `cond` is false, otherwise IO.unit
   *
   * @example {{{
   * val tooMany = 5
   * val x: Int = ???
   * IO.raiseUnless(x < tooMany)(new IllegalArgumentException("Too many"))
   * }}}
   */
  def raiseUnless(cond: Boolean)(e: => Throwable): IO[Unit] =
    IO.unlessA(cond)(IO.raiseError(e))

  /**
   * Reads a line as a string from the standard input using the platform's
   * default charset, as per `java.nio.charset.Charset.defaultCharset()`.
   *
   * The effect can raise a `java.io.EOFException` if no input has been consumed
   * before the EOF is observed. This should never happen with the standard
   * input, unless it has been replaced with a finite `java.io.InputStream`
   * through `java.lang.System#setIn` or similar.
   *
   * @see `cats.effect.std.Console#readLineWithCharset` for reading using a
   * custom `java.nio.charset.Charset`
   *
   * @return an IO effect that describes reading the user's input from the
   *         standard input as a string
   */
  def readLine: IO[String] =
    Console[IO].readLine

  /**
   * Prints a value to the standard output using the implicit `cats.Show`
   * instance.
   *
   * @see `cats.effect.std.Console` for more standard input, output and error
   * operations
   *
   * @param a value to be printed to the standard output
   */
  def print[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): IO[Unit] =
    Console[IO].print(a)

  /**
   * Prints a value to the standard output followed by a new line using the
   * implicit `cats.Show` instance.
   *
   * @see `cats.effect.std.Console` for more standard input, output and error
   * operations
   *
   * @param a value to be printed to the standard output
   */
  def println[A](a: A)(implicit S: Show[A] = Show.fromToString[A]): IO[Unit] =
    Console[IO].println(a)

  /**
   * Lifts an `Eval` into `IO`.
   *
   * This function will preserve the evaluation semantics of any
   * actions that are lifted into the pure `IO`.  Eager `Eval`
   * instances will be converted into thunk-less `IO` (i.e. eager
   * `IO`), while lazy eval and memoized will be executed as such.
   */
  def eval[A](fa: Eval[A]): IO[A] =
    fa match {
      case Now(a) => pure(a)
      case notNow => apply(notNow.value)
    }

  /**
   * Lifts an `Option[A]` into the `IO[A]` context, raising the throwable if the option is empty.
   */
  def fromOption[A](o: Option[A])(orElse: => Throwable): IO[A] =
    o match {
      case None => raiseError(orElse)
      case Some(value) => pure(value)
    }

  /**
   * Lifts an `Either[Throwable, A]` into the `IO[A]` context, raising
   * the throwable if it exists.
   */
  def fromEither[A](e: Either[Throwable, A]): IO[A] =
    e match {
      case Right(a) => pure(a)
      case Left(err) => raiseError(err)
    }

  /**
   * Lifts an `Try[A]` into the `IO[A]` context, raising the throwable if it
   * exists.
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
      a.handleErrorWith(_ => b)
  }

  implicit def alignForIO: Align[IO] = _alignForIO

  private[this] val _alignForIO = new Align[IO] {
    def align[A, B](fa: IO[A], fb: IO[B]): IO[Ior[A, B]] =
      alignWith(fa, fb)(identity)

    override def alignWith[A, B, C](fa: IO[A], fb: IO[B])(f: Ior[A, B] => C): IO[C] =
      fa.redeemWith(
        t => fb.redeemWith(_ => IO.raiseError(t), b => IO.pure(f(Ior.right(b)))),
        a => fb.redeem(_ => f(Ior.left(a)), b => f(Ior.both(a, b)))
      )

    def functor: Functor[IO] = Functor[IO]
  }

  private[this] val _asyncForIO: kernel.Async[IO] = new kernel.Async[IO]
    with StackSafeMonad[IO] {

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

    override def timeoutTo[A](ioa: IO[A], duration: FiniteDuration, fallback: IO[A]): IO[A] =
      ioa.timeoutTo(duration, fallback)

    def canceled: IO[Unit] =
      IO.canceled

    def cede: IO[Unit] = IO.cede

    override def productL[A, B](left: IO[A])(right: IO[B]): IO[A] =
      left.productL(right)

    override def productR[A, B](left: IO[A])(right: IO[B]): IO[B] =
      left.productR(right)

    def start[A](fa: IO[A]): IO[FiberIO[A]] =
      fa.start

    def uncancelable[A](body: Poll[IO] => IO[A]): IO[A] =
      IO.uncancelable(body)

    override def map[A, B](fa: IO[A])(f: A => B): IO[B] =
      fa.map(f)

    def flatMap[A, B](fa: IO[A])(f: A => IO[B]): IO[B] =
      fa.flatMap(f)

    override def delay[A](thunk: => A): IO[A] = IO(thunk)

    override def blocking[A](thunk: => A): IO[A] = IO.blocking(thunk)

    override def interruptible[A](many: Boolean)(thunk: => A): IO[A] =
      IO.interruptible(many)(thunk)

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
  }

  implicit def asyncForIO: kernel.Async[IO] = _asyncForIO

  private[this] val _parallelForIO: Parallel.Aux[IO, Par] =
    spawn.parallelForGenSpawn[IO, Throwable]

  implicit def parallelForIO: Parallel.Aux[IO, Par] = _parallelForIO

  implicit val consoleForIO: Console[IO] =
    Console.make

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
    final case class UnmaskRunLoop[+A](ioa: IO[A], id: Int) extends IO[A] {
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

  // INTERNAL, only created by the runloop itself as the terminal state of several operations
  private[effect] case object EndFiber extends IO[Nothing] {
    def tag = -1
  }

}
