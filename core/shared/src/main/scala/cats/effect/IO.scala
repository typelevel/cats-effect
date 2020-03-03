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

import cats.effect.internals._
import cats.effect.internals.IOPlatform.fusionMaxStackDepth

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise, TimeoutException}
import scala.util.control.NonFatal
import scala.util.{Failure, Left, Right, Success, Try}
import cats.data.Ior

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
 *  1. can end in either success or failure and in case of failure
 *     `flatMap` chains get short-circuited (`IO` implementing
 *     the algebra of `MonadError`)
 *  1. can be canceled, but note this capability relies on the
 *     user to provide cancellation logic
 *
 * Effects described via this abstraction are not evaluated until
 * the "end of the world", which is to say, when one of the "unsafe"
 * methods are used. Effectful results are not memoized, meaning that
 * memory overhead is minimal (and no leaks), and also that a single
 * effect may be run multiple times in a referentially-transparent
 * manner. For example:
 *
 * {{{
 * val ioa = IO { println("hey!") }
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
 *   def fib(n: Int, a: Long = 0, b: Long = 1): IO[Long] =
 *     IO(a + b).flatMap { b2 =>
 *       if (n > 0)
 *         fib(n - 1, b, b2)
 *       else
 *         IO.pure(b2)
 *     }
 * }}}
 */
sealed abstract class IO[+A] extends internals.IOBinaryCompat[A] {
  import IO._

  /**
   * Replaces the result of this IO with the given value.
   * The value won't be computed if the IO doesn't succeed.
   * */
  def as[B](newValue: => B): IO[B] = map(_ => newValue)

  /**
   * Functor map on `IO`. Given a mapping function, it transforms the
   * value produced by the source, while keeping the `IO` context.
   *
   * Any exceptions thrown within the function will be caught and
   * sequenced into the `IO`, because due to the nature of
   * asynchronous processes, without catching and handling exceptions,
   * failures would be completely silent and `IO` references would
   * never terminate on evaluation.
   */
  final def map[B](f: A => B): IO[B] =
    this match {
      case Map(source, g, index) =>
        // Allowed to do fixed number of map operations fused before
        // resetting the counter in order to avoid stack overflows;
        // See `IOPlatform` for details on this maximum.
        if (index != fusionMaxStackDepth) Map(source, g.andThen(f), index + 1)
        else Map(this, f, 0)
      case _ =>
        Map(this, f, 0)
    }

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
  final def flatMap[B](f: A => IO[B]): IO[B] =
    Bind(this, f)

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
    Bind(this, AttemptIO.asInstanceOf[A => IO[Either[Throwable, A]]])

  /**
   * Produces an `IO` reference that should execute the source on
   * evaluation, without waiting for its result, being the safe
   * analogue to [[unsafeRunAsync]].
   *
   * This operation is isomorphic to [[unsafeRunAsync]]. What it does
   * is to let you describe asynchronous execution with a function
   * that stores off the results of the original `IO` as a
   * side effect, thus ''avoiding'' the usage of impure callbacks or
   * eager evaluation.
   *
   * The returned `IO` is guaranteed to execute immediately,
   * and does not wait on any async action to complete, thus this
   * is safe to do, even on top of runtimes that cannot block threads
   * (e.g. JavaScript):
   *
   * {{{
   *   // Sample
   *   val source = IO.shift *> IO(1)
   *   // Describes execution
   *   val start = source.runAsync {
   *     case Left(e) => IO(e.printStackTrace())
   *     case Right(_) => IO.unit
   *   }
   *   // Safe, because it does not block for the source to finish
   *   start.unsafeRunSync
   * }}}
   *
   * @return an `IO` value that upon evaluation will execute the source,
   *         but will not wait for its completion
   *
   * @see [[runCancelable]] for the version that gives you a cancelable
   *      token that can be used to send a cancel signal
   */
  final def runAsync(cb: Either[Throwable, A] => IO[Unit]): SyncIO[Unit] = SyncIO {
    unsafeRunAsync(cb.andThen(_.unsafeRunAsyncAndForget()))
  }

  /**
   * Produces an `IO` reference that should execute the source on evaluation,
   * without waiting for its result and return a cancelable token, being the
   * safe analogue to [[unsafeRunCancelable]].
   *
   * This operation is isomorphic to [[unsafeRunCancelable]]. Just like
   * [[runAsync]], this operation avoids the usage of impure callbacks or
   * eager evaluation.
   *
   * The returned `IO` boxes an `IO[Unit]` that can be used to cancel the
   * running asynchronous computation (if the source can be canceled).
   *
   * The returned `IO` is guaranteed to execute immediately,
   * and does not wait on any async action to complete, thus this
   * is safe to do, even on top of runtimes that cannot block threads
   * (e.g. JavaScript):
   *
   * {{{
   *   val source: IO[Int] = ???
   *   // Describes interruptible execution
   *   val start: IO[CancelToken[IO]] = source.runCancelable
   *
   *   // Safe, because it does not block for the source to finish
   *   val cancel: IO[Unit] = start.unsafeRunSync
   *
   *   // Safe, because cancellation only sends a signal,
   *   // but doesn't back-pressure on anything
   *   cancel.unsafeRunSync
   * }}}
   *
   * @return an `IO` value that upon evaluation will execute the source,
   *         but will not wait for its completion, yielding a cancellation
   *         token that can be used to cancel the async process
   *
   * @see [[runAsync]] for the simple, uninterruptible version
   */
  final def runCancelable(cb: Either[Throwable, A] => IO[Unit]): SyncIO[CancelToken[IO]] =
    SyncIO(unsafeRunCancelable(cb.andThen(_.unsafeRunAsync(_ => ()))))

  /**
   * Produces the result by running the encapsulated effects as impure
   * side effects.
   *
   * If any component of the computation is asynchronous, the current
   * thread will block awaiting the results of the async computation.
   * On JavaScript, an exception will be thrown instead to avoid
   * generating a deadlock. By default, this blocking will be
   * unbounded.  To limit the thread block to some fixed time, use
   * `unsafeRunTimed` instead.
   *
   * Any exceptions raised within the effect will be re-thrown during
   * evaluation.
   *
   * As the name says, this is an UNSAFE function as it is impure and
   * performs side effects, not to mention blocking, throwing
   * exceptions, and doing other things that are at odds with
   * reasonable software.  You should ideally only call this function
   * *once*, at the very end of your program.
   */
  final def unsafeRunSync(): A = unsafeRunTimed(Duration.Inf).get

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
  final def unsafeRunAsync(cb: Either[Throwable, A] => Unit): Unit =
    IORunLoop.start(this, cb)

  /**
   * Triggers the evaluation of the source and any suspended side
   * effects therein, but ignores the result.
   *
   * This operation is similar to [[unsafeRunAsync]], in that the
   * evaluation can happen asynchronously (depending on the source),
   * however no callback is required, therefore the result gets
   * ignored.
   *
   * Note that errors still get logged (via IO's internal logger),
   * because errors being thrown should never be totally silent.
   */
  final def unsafeRunAsyncAndForget(): Unit =
    unsafeRunAsync(Callback.report)

  /**
   * Evaluates the source `IO`, passing the result of the encapsulated
   * effects to the given callback.
   *
   * As the name says, this is an UNSAFE function as it is impure and
   * performs side effects.  You should ideally only call this
   * function ''once'', at the very end of your program.
   *
   * @return an side-effectful function that, when executed, sends a
   *         cancellation reference to `IO`'s run-loop implementation,
   *         having the potential to interrupt it.
   */
  final def unsafeRunCancelable(cb: Either[Throwable, A] => Unit): CancelToken[IO] = {
    val conn = IOConnection()
    IORunLoop.startCancelable(this, conn, cb)
    conn.cancel
  }

  /**
   * Similar to `unsafeRunSync`, except with a bounded blocking
   * duration when awaiting asynchronous results.
   *
   * Please note that the `limit` parameter does not limit the time of
   * the total computation, but rather acts as an upper bound on any
   * *individual* asynchronous block.  Thus, if you pass a limit of `5
   * seconds` to an `IO` consisting solely of synchronous actions, the
   * evaluation may take considerably longer than 5 seconds!
   * Furthermore, if you pass a limit of `5 seconds` to an `IO`
   * consisting of several asynchronous actions joined together,
   * evaluation may take up to `n * 5 seconds`, where `n` is the
   * number of joined async actions.
   *
   * As soon as an async blocking limit is hit, evaluation
   * ''immediately'' aborts and `None` is returned.
   *
   * Please note that this function is intended for ''testing''; it
   * should never appear in your mainline production code!  It is
   * absolutely not an appropriate function to use if you want to
   * implement timeouts, or anything similar. If you need that sort
   * of functionality, you should be using a streaming library (like
   * fs2 or Monix).
   *
   * @see [[unsafeRunSync]]
   * @see [[timeout]] for pure and safe version
   */
  final def unsafeRunTimed(limit: Duration): Option[A] =
    IORunLoop.step(this) match {
      case Pure(a)       => Some(a)
      case RaiseError(e) => throw e
      case self @ Async(_, _) =>
        IOPlatform.unsafeResync(self, limit)
      case _ =>
        // $COVERAGE-OFF$
        throw new AssertionError("unreachable")
      // $COVERAGE-ON$
    }

  /**
   * Evaluates the effect and produces the result in a `Future`.
   *
   * This is similar to `unsafeRunAsync` in that it evaluates the `IO`
   * as a side effect in a non-blocking fashion, but uses a `Future`
   * rather than an explicit callback.  This function should really
   * only be used if interoperating with legacy code which uses Scala
   * futures.
   *
   * @see [[IO.fromFuture]]
   */
  final def unsafeToFuture(): Future[A] = {
    val p = Promise[A]
    unsafeRunAsync { cb =>
      cb.fold(p.failure, p.success)
      ()
    }
    p.future
  }

  /**
   * Start execution of the source suspended in the `IO` context.
   *
   * This can be used for non-deterministic / concurrent execution.
   * The following code is more or less equivalent with `parMap2`
   * (minus the behavior on error handling and cancellation):
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
   * still recommended because of behavior on error and cancellation —
   * consider in the example above what would happen if the first task
   * finishes in error. In that case the second task doesn't get canceled,
   * which creates a potential memory leak.
   *
   * Also see [[background]] for a safer alternative.
   */
  final def start(implicit cs: ContextShift[IO]): IO[Fiber[IO, A @uncheckedVariance]] =
    IOStart(cs, this)

  /**
   * Returns a resource that will start execution of this IO in the background.
   *
   * In case the resource is closed while this IO is still running (e.g. due to a failure in `use`),
   * the background action will be canceled.
   *
   * @see [[cats.effect.Concurrent#background]] for the generic version.
   */
  final def background(implicit cs: ContextShift[IO]): Resource[IO, IO[A @uncheckedVariance]] =
    Resource.make(start)(_.cancel).map(_.join)

  /**
   * Makes the source `IO` uninterruptible such that a [[Fiber.cancel]]
   * signal has no effect.
   */
  final def uncancelable: IO[A] =
    IOCancel.uncancelable(this)

  /**
   * Converts the source `IO` into any `F` type that implements
   * the [[LiftIO]] type class.
   */
  final def to[F[_]](implicit F: LiftIO[F]): F[A @uncheckedVariance] =
    F.liftIO(this)

  /**
   * Returns an IO that either completes with the result of the source within
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
   *
   * @param timer is an implicit requirement for the ability to do a fiber
   *        [[Timer.sleep sleep]] for the specified timeout, at which point
   *        the fallback needs to be triggered
   *
   * @param cs is an implicit requirement for the ability to trigger a
   *        [[IO.race race]], needed because IO's `race` operation automatically
   *        forks the involved tasks
   */
  final def timeoutTo[A2 >: A](duration: FiniteDuration, fallback: IO[A2])(implicit timer: Timer[IO],
                                                                           cs: ContextShift[IO]): IO[A2] =
    Concurrent.timeoutTo(this, duration, fallback)

  /**
   * Returns an IO that either completes with the result of the source within
   * the specified time `duration` or otherwise raises a `TimeoutException`.
   *
   * The source is cancelled in the event that it takes longer than
   * the specified time duration to complete.
   *
   * @param duration is the time span for which we wait for the source to
   *        complete; in the event that the specified time has passed without
   *        the source completing, a `TimeoutException` is raised
   *
   * @param timer is an implicit requirement for the ability to do a fiber
   *        [[Timer.sleep sleep]] for the specified timeout, at which point
   *        the fallback needs to be triggered
   *
   * @param cs is an implicit requirement for the ability to trigger a
   *        [[IO.race race]], needed because IO's `race` operation automatically
   *        forks the involved tasks
   */
  final def timeout(duration: FiniteDuration)(implicit timer: Timer[IO], cs: ContextShift[IO]): IO[A] =
    timeoutTo(duration, IO.raiseError(new TimeoutException(duration.toString)))

  /**
   * Returns an IO that will delay the execution of the source by the given duration.
   * */
  final def delayBy(duration: FiniteDuration)(implicit timer: Timer[IO]): IO[A] =
    timer.sleep(duration) *> this

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
   * during the computation, or in case of cancellation.
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
   * Note that in case of cancellation the underlying implementation
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
   * termination and cancellation.
   *
   * '''NOTE on error handling''': one big difference versus
   * `try/finally` statements is that, in case both the `release`
   * function and the `use` function throws, the error raised by `use`
   * gets signaled.
   *
   * For example:
   *
   * {{{
   *   IO("resource").bracket { _ =>
   *     // use
   *     IO.raiseError(new RuntimeException("Foo"))
   *   } { _ =>
   *     // release
   *     IO.raiseError(new RuntimeException("Bar"))
   *   }
   * }}}
   *
   * In this case the error signaled downstream is `"Foo"`, while the
   * `"Bar"` error gets reported. This is consistent with the behavior
   * of Haskell's `bracket` operation and NOT with `try {} finally {}`
   * from Scala, Java or JavaScript.
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
  final def bracket[B](use: A => IO[B])(release: A => IO[Unit]): IO[B] =
    bracketCase(use)((a, _) => release(a))

  /**
   * Returns a new `IO` task that treats the source task as the
   * acquisition of a resource, which is then exploited by the `use`
   * function and then `released`, with the possibility of
   * distinguishing between normal termination and cancellation, such
   * that an appropriate release of resources can be executed.
   *
   * The `bracketCase` operation is the equivalent of
   * `try {} catch {} finally {}` statements from mainstream languages
   * when used for the acquisition and release of resources.
   *
   * The `bracketCase` operation installs the necessary exception handler
   * to release the resource in the event of an exception being raised
   * during the computation, or in case of cancellation.
   *
   * In comparison with the simpler [[bracket]] version, this one
   * allows the caller to differentiate between normal termination,
   * termination in error and cancellation via an [[ExitCase]]
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
   *        (cancellation, error or successful result)
   */
  def bracketCase[B](use: A => IO[B])(release: (A, ExitCase[Throwable]) => IO[Unit]): IO[B] =
    IOBracket(this)(use)(release)

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
   * As best practice, it's not a good idea to release resources
   * via `guaranteeCase` in polymorphic code. Prefer [[bracket]]
   * for the acquisition and release of resources.
   *
   * @see [[guaranteeCase]] for the version that can discriminate
   *      between termination conditions
   *
   * @see [[bracket]] for the more general operation
   */
  def guarantee(finalizer: IO[Unit]): IO[A] =
    guaranteeCase(_ => finalizer)

  /**
   * Executes the given `finalizer` when the source is finished,
   * either in success or in error, or if canceled, allowing
   * for differentiating between exit conditions.
   *
   * This variant of [[guarantee]] injects an [[ExitCase]] in
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
   * As best practice, it's not a good idea to release resources
   * via `guaranteeCase` in polymorphic code. Prefer [[bracketCase]]
   * for the acquisition and release of resources.
   *
   * @see [[guarantee]] for the simpler version
   *
   * @see [[bracketCase]] for the more general operation
   */
  def guaranteeCase(finalizer: ExitCase[Throwable] => IO[Unit]): IO[A] =
    IOBracket.guaranteeCase(this, finalizer)

  /**
   * Replaces failures in this IO with an empty Option.
   */
  def option: IO[Option[A]] = redeem(_ => None, Some(_))

  /**
   * Handle any error, potentially recovering from it, by mapping it to another
   * `IO` value.
   *
   * Implements `ApplicativeError.handleErrorWith`.
   */
  def handleErrorWith[AA >: A](f: Throwable => IO[AA]): IO[AA] =
    IO.Bind(this, new IOFrame.ErrorHandler(f))

  /**
   * Zips both this action and the parameter in parallel.
   * If [[parProduct]] is canceled, both actions are canceled.
   * Failure in either of the IOs will cancel the other one.
   *  */
  def parProduct[B](another: IO[B])(implicit p: NonEmptyParallel[IO]): IO[(A, B)] =
    p.sequential(p.apply.product(p.parallel(this), p.parallel(another)))

  /**
   * Returns a new value that transforms the result of the source,
   * given the `recover` or `map` functions, which get executed depending
   * on whether the result is successful or if it ends in error.
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
    IO.Bind(this, new IOFrame.Redeem(recover, map))

  /**
   * Returns a new value that transforms the result of the source,
   * given the `recover` or `bind` functions, which get executed depending
   * on whether the result is successful or if it ends in error.
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
    IO.Bind(this, new IOFrame.RedeemWith(recover, bind))

  override def toString: String = this match {
    case Pure(a)       => s"IO($a)"
    case RaiseError(e) => s"IO(throw $e)"
    case _             => "IO$" + System.identityHashCode(this)
  }

  /*
   * Ignores the result of this IO.
   */
  def void: IO[Unit] = map(_ => ())

  /**
   * Runs the current IO, then runs the parameter, keeping its result.
   * The result of the first action is ignored.
   * If the source fails, the other action won't run.
   * */
  def *>[B](another: IO[B]): IO[B] = flatMap(_ => another)

  /**
   * Like [[*>]], but keeps the result of the source.
   *
   * For a similar method that also runs the parameter in case of failure or interruption, see [[guarantee]].
   * */
  def <*[B](another: IO[B]): IO[A] = flatMap(another.as(_))

  /**
   * Runs this IO and the parameter in parallel.
   *
   * Failure in either of the IOs will cancel the other one.
   * If the whole computation is canceled, both actions are also canceled.
   * */
  def &>[B](another: IO[B])(implicit p: NonEmptyParallel[IO]): IO[B] =
    p.parProductR(this)(another)

  /**
   * Like [[&>]], but keeps the result of the source.
   * */
  def <&[B](another: IO[B])(implicit p: NonEmptyParallel[IO]): IO[A] =
    p.parProductL(this)(another)
}

abstract private[effect] class IOParallelNewtype extends internals.IOTimerRef with internals.IOCompanionBinaryCompat {

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
  type Par[+A] = Par.Type[A]

  /**
   * Newtype encoding, see the [[IO.Par]] type alias
   * for more details.
   */
  object Par extends IONewtype

  private[effect] def ioParAlign(implicit cs: ContextShift[IO]): Align[IO.Par] = new Align[IO.Par] {
    import IO.Par.{unwrap, apply => par}

    def align[A, B](fa: IO.Par[A], fb: IO.Par[B]): IO.Par[Ior[A, B]] = alignWith(fa, fb)(identity)

    override def alignWith[A, B, C](fa: IO.Par[A], fb: IO.Par[B])(f: Ior[A, B] => C): IO.Par[C] =
      par(
        IOParMap(cs, unwrap(fa).attempt, unwrap(fb).attempt)((ea, eb) =>
          cats.instances.either.catsStdInstancesForEither.alignWith(ea, eb)(f)
        ).flatMap(IO.fromEither)
      )

    def functor: Functor[IO.Par] = ioParCommutativeApplicative
  }

  private[effect] def ioParCommutativeApplicative(implicit cs: ContextShift[IO]): CommutativeApplicative[IO.Par] =
    new CommutativeApplicative[IO.Par] {
      import IO.Par.{unwrap, apply => par}

      final override def pure[A](x: A): IO.Par[A] =
        par(IO.pure(x))
      final override def map2[A, B, Z](fa: IO.Par[A], fb: IO.Par[B])(f: (A, B) => Z): IO.Par[Z] =
        par(IOParMap(cs, unwrap(fa), unwrap(fb))(f))
      final override def ap[A, B](ff: IO.Par[A => B])(fa: IO.Par[A]): IO.Par[B] =
        map2(ff, fa)(_(_))
      final override def product[A, B](fa: IO.Par[A], fb: IO.Par[B]): IO.Par[(A, B)] =
        map2(fa, fb)((_, _))
      final override def map[A, B](fa: IO.Par[A])(f: A => B): IO.Par[B] =
        par(unwrap(fa).map(f))
      final override def unit: IO.Par[Unit] =
        par(IO.unit)
    }
}

abstract private[effect] class IOLowPriorityInstances extends IOParallelNewtype {
  implicit def parApplicative(implicit cs: ContextShift[IO]): Applicative[IO.Par] = ioParCommutativeApplicative

  private[effect] class IOSemigroup[A: Semigroup] extends Semigroup[IO[A]] {
    def combine(ioa1: IO[A], ioa2: IO[A]) =
      ioa1.flatMap(a1 => ioa2.map(a2 => Semigroup[A].combine(a1, a2)))
  }

  implicit def ioSemigroup[A: Semigroup]: Semigroup[IO[A]] = new IOSemigroup[A]

  implicit val ioEffect: Effect[IO] = new IOEffect

  private[effect] class IOEffect extends Effect[IO] with StackSafeMonad[IO] {
    final override def pure[A](a: A): IO[A] =
      IO.pure(a)
    final override def unit: IO[Unit] =
      IO.unit

    final override def map[A, B](fa: IO[A])(f: A => B): IO[B] =
      fa.map(f)
    final override def flatMap[A, B](ioa: IO[A])(f: A => IO[B]): IO[B] =
      ioa.flatMap(f)

    final override def attempt[A](ioa: IO[A]): IO[Either[Throwable, A]] =
      ioa.attempt
    final override def handleErrorWith[A](ioa: IO[A])(f: Throwable => IO[A]): IO[A] =
      ioa.handleErrorWith(f)
    final override def raiseError[A](e: Throwable): IO[A] =
      IO.raiseError(e)

    final override def bracket[A, B](acquire: IO[A])(use: A => IO[B])(release: A => IO[Unit]): IO[B] =
      acquire.bracket(use)(release)

    final override def uncancelable[A](task: IO[A]): IO[A] =
      task.uncancelable

    final override def bracketCase[A, B](
      acquire: IO[A]
    )(use: A => IO[B])(release: (A, ExitCase[Throwable]) => IO[Unit]): IO[B] =
      acquire.bracketCase(use)(release)

    final override def guarantee[A](fa: IO[A])(finalizer: IO[Unit]): IO[A] =
      fa.guarantee(finalizer)
    final override def guaranteeCase[A](fa: IO[A])(finalizer: ExitCase[Throwable] => IO[Unit]): IO[A] =
      fa.guaranteeCase(finalizer)

    final override def delay[A](thunk: => A): IO[A] =
      IO.delay(thunk)
    final override def suspend[A](thunk: => IO[A]): IO[A] =
      IO.suspend(thunk)
    final override def async[A](k: (Either[Throwable, A] => Unit) => Unit): IO[A] =
      IO.async(k)
    final override def asyncF[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): IO[A] =
      IO.asyncF(k)
    override def liftIO[A](ioa: IO[A]): IO[A] =
      ioa
    override def toIO[A](fa: IO[A]): IO[A] =
      fa
    final override def runAsync[A](ioa: IO[A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[Unit] =
      ioa.runAsync(cb)
  }
}

abstract private[effect] class IOInstances extends IOLowPriorityInstances {
  implicit def parCommutativeApplicative(implicit cs: ContextShift[IO]): CommutativeApplicative[IO.Par] =
    ioParCommutativeApplicative

  implicit def parAlign(implicit cs: ContextShift[IO]): Align[IO.Par] =
    ioParAlign

  implicit def ioConcurrentEffect(implicit cs: ContextShift[IO]): ConcurrentEffect[IO] =
    new IOEffect with ConcurrentEffect[IO] {
      final override def start[A](fa: IO[A]): IO[Fiber[IO, A]] =
        fa.start
      final override def race[A, B](fa: IO[A], fb: IO[B]): IO[Either[A, B]] =
        IO.race(fa, fb)
      final override def racePair[A, B](fa: IO[A], fb: IO[B]): IO[Either[(A, Fiber[IO, B]), (Fiber[IO, A], B)]] =
        IO.racePair(fa, fb)
      final override def cancelable[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): IO[A] =
        IO.cancelable(k)
      final override def runCancelable[A](fa: IO[A])(cb: Either[Throwable, A] => IO[Unit]): SyncIO[CancelToken[IO]] =
        fa.runCancelable(cb)

      final override def toIO[A](fa: IO[A]): IO[A] = fa
      final override def liftIO[A](ioa: IO[A]): IO[A] = ioa
    }

  implicit val ioAlign: Align[IO] = new Align[IO] {
    def align[A, B](fa: IO[A], fb: IO[B]): IO[Ior[A, B]] =
      alignWith(fa, fb)(identity)

    override def alignWith[A, B, C](fa: IO[A], fb: IO[B])(f: Ior[A, B] => C): IO[C] =
      fa.redeemWith(
        t => fb.redeemWith(_ => IO.raiseError(t), b => IO.pure(f(Ior.right(b)))),
        a => fb.redeem(_ => f(Ior.left(a)), b => f(Ior.both(a, b)))
      )

    def functor: Functor[IO] = Functor[IO]
  }

  implicit def ioParallel(implicit cs: ContextShift[IO]): Parallel.Aux[IO, IO.Par] =
    new Parallel[IO] {
      type F[x] = IO.Par[x]

      final override val applicative: Applicative[IO.Par] =
        parApplicative(cs)
      final override val monad: Monad[IO] =
        ioConcurrentEffect(cs)

      final override val sequential: IO.Par ~> IO = λ[IO.Par ~> IO](IO.Par.unwrap(_))

      final override val parallel: IO ~> IO.Par = λ[IO ~> IO.Par](IO.Par(_))
    }

  implicit def ioMonoid[A: Monoid]: Monoid[IO[A]] = new IOSemigroup[A] with Monoid[IO[A]] {
    final override def empty: IO[A] = IO.pure(Monoid[A].empty)
  }

  implicit val ioSemigroupK: SemigroupK[IO] = new SemigroupK[IO] {
    final override def combineK[A](a: IO[A], b: IO[A]): IO[A] =
      ApplicativeError[IO, Throwable].handleErrorWith(a)(_ => b)
  }
}

/**
 * @define shiftDesc Note there are 2 overloads of the `IO.shift` function:
 *
 *         - One that takes a `ContextShift` that manages the
 *           thread-pool used to trigger async boundaries.
 *         - Another that takes a Scala `ExecutionContext` as the
 *           thread-pool.
 *
 *         Please use the former by default and use the latter
 *         only for fine-grained control over the thread pool in
 *         use.
 *
 *         By default, Cats Effect can provide instance of
 *         `ContextShift[IO]` that manages thread-pools, but
 *         only if there’s an `ExecutionContext` in scope or
 *         if `IOApp` is used:
 *
 *         {{{
 *           import cats.effect.{IO, ContextShift}
 *
 *           val ec = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(...))
 *           val contextShift = IO.contextShift(ec)
 *         }}}
 *
 *         For example we can introduce an asynchronous
 *         boundary in the `flatMap` chain before a certain task:
 *         {{{
 *           val task = IO(println("task"))
 *
 *           IO.shift(contextShift).flatMap(_ => task)
 *         }}}
 *
 *         Note that the ContextShift value is taken implicitly
 *         from the context so you can just do this:
 *
 *         {{{
 *           IO.shift.flatMap(_ => task)
 *         }}}
 *
 *         Or using Cats syntax:
 *         {{{
 *           import cats.syntax.apply._
 *
 *           IO.shift *> task
 *           // equivalent to
 *           ContextShift[IO].shift *> task
 *         }}}
 *
 *         Or we can specify an asynchronous boundary ''after'' the
 *         evaluation of a certain task:
 *         {{{
 *           task.flatMap(a => IO.shift.map(_ => a))
 *         }}}
 *
 *         Or using Cats syntax:
 *         {{{
 *           task <* IO.shift
 *           // equivalent to
 *          task <* ContextShift[IO].shift
 *         }}}
 *
 *         Example of where this might be useful:
 *         {{{
 *         for {
 *           _ <- IO.shift(BlockingIO)
 *           bytes <- readFileUsingJavaIO(file)
 *           _ <- IO.shift(DefaultPool)
 *
 *           secure = encrypt(bytes, KeyManager)
 *           _ <- sendResponse(Protocol.v1, secure)
 *
 *           _ <- IO { println("it worked!") }
 *         } yield ()
 *         }}}
 *
 *         In the above, `readFileUsingJavaIO` will be shifted to the
 *         pool represented by `BlockingIO`, so long as it is defined
 *         using `apply` or `suspend` (which, judging by the name, it
 *         probably is).  Once its computation is complete, the rest
 *         of the `for`-comprehension is shifted ''again'', this time
 *         onto the `DefaultPool`.  This pool is used to compute the
 *         encrypted version of the bytes, which are then passed to
 *         `sendResponse`.  If we assume that `sendResponse` is
 *         defined using `async` (perhaps backed by an NIO socket
 *         channel), then we don't actually know on which pool the
 *         final `IO` action (the `println`) will be run.  If we
 *         wanted to ensure that the `println` runs on `DefaultPool`,
 *         we would insert another `shift` following `sendResponse`.
 *
 *         Another somewhat less common application of `shift` is to
 *         reset the thread stack and yield control back to the
 *         underlying pool. For example:
 *
 *         {{{
 *         lazy val repeat: IO[Unit] = for {
 *           _ <- doStuff
 *           _ <- IO.shift
 *           _ <- repeat
 *         } yield ()
 *         }}}
 *
 *         In this example, `repeat` is a very long running `IO`
 *         (infinite, in fact!) which will just hog the underlying
 *         thread resource for as long as it continues running.  This
 *         can be a bit of a problem, and so we inject the `IO.shift`
 *         which yields control back to the underlying thread pool,
 *         giving it a chance to reschedule things and provide better
 *         fairness.  This shifting also "bounces" the thread stack,
 *         popping all the way back to the thread pool and effectively
 *         trampolining the remainder of the computation.  This sort
 *         of manual trampolining is unnecessary if `doStuff` is
 *         defined using `suspend` or `apply`, but if it was defined
 *         using `async` and does ''not'' involve any real
 *         concurrency, the call to `shift` will be necessary to avoid
 *         a `StackOverflowError`.
 *
 *         Thus, this function has four important use cases:
 *
 *          - shifting blocking actions off of the main compute pool,
 *          - defensively re-shifting asynchronous continuations back
 *            to the main compute pool
 *          - yielding control to some underlying pool for fairness
 *            reasons, and
 *          - preventing an overflow of the call stack in the case of
 *            improperly constructed `async` actions
 *
 *          Note there are 2 overloads of this function:
 *
 *          - one that takes an [[Timer]] ([[IO.shift(implicit* link]])
 *          - one that takes a Scala `ExecutionContext` ([[IO.shift(ec* link]])
 *
 *          Use the former by default, use the later for fine grained
 *          control over the thread pool used.
 */
object IO extends IOInstances {

  /**
   * Suspends a synchronous side effect in `IO`.
   *
   * Alias for `IO.delay(body)`.
   */
  def apply[A](body: => A): IO[A] =
    delay(body)

  /**
   * Suspends a synchronous side effect in `IO`.
   *
   * Any exceptions thrown by the effect will be caught and sequenced
   * into the `IO`.
   */
  def delay[A](body: => A): IO[A] =
    Delay(() => body)

  /**
   * Suspends a synchronous side effect which produces an `IO` in `IO`.
   *
   * This is useful for trampolining (i.e. when the side effect is
   * conceptually the allocation of a stack frame).  Any exceptions
   * thrown by the side effect will be caught and sequenced into the
   * `IO`.
   */
  def suspend[A](thunk: => IO[A]): IO[A] =
    Suspend(() => thunk)

  /**
   * Suspends a pure value in `IO`.
   *
   * This should ''only'' be used if the value in question has
   * "already" been computed!  In other words, something like
   * `IO.pure(readLine)` is most definitely not the right thing to do!
   * However, `IO.pure(42)` is correct and will be more efficient
   * (when evaluated) than `IO(42)`, due to avoiding the allocation of
   * extra thunks.
   */
  def pure[A](a: A): IO[A] = Pure(a)

  /** Alias for `IO.pure(())`. */
  val unit: IO[Unit] = pure(())

  /**
   * A non-terminating `IO`, alias for `async(_ => ())`.
   */
  val never: IO[Nothing] = async(_ => ())

  /**
   * An IO that contains an empty Option.
   */
  def none[A]: IO[Option[A]] = pure(None)

  /**
   * Lifts an `Eval` into `IO`.
   *
   * This function will preserve the evaluation semantics of any
   * actions that are lifted into the pure `IO`.  Eager `Eval`
   * instances will be converted into thunk-less `IO` (i.e. eager
   * `IO`), while lazy eval and memoized will be executed as such.
   */
  def eval[A](fa: Eval[A]): IO[A] = fa match {
    case Now(a) => pure(a)
    case notNow => apply(notNow.value)
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
   * def fork[A](body: => A)(implicit E: ExecutorService): IO[A] = {
   *   IO async { cb =>
   *     E.execute(new Runnable {
   *       def run() =
   *         try cb(Right(body)) catch { case NonFatal(t) => cb(Left(t)) }
   *     })
   *   }
   * }
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
   * @see [[asyncF]] and [[cancelable]]
   */
  def async[A](k: (Either[Throwable, A] => Unit) => Unit): IO[A] =
    Async { (_, cb) =>
      val cb2 = Callback.asyncIdempotent(null, cb)
      try k(cb2)
      catch { case NonFatal(t) => cb2(Left(t)) }
    }

  /**
   * Suspends an asynchronous side effect in `IO`, this being a variant
   * of [[async]] that takes a pure registration function.
   *
   * Implements [[cats.effect.Async.asyncF Async.asyncF]].
   *
   * The difference versus [[async]] is that this variant can suspend
   * side-effects via the provided function parameter. It's more relevant
   * in polymorphic code making use of the [[cats.effect.Async Async]]
   * type class, as it alleviates the need for [[Effect]].
   *
   * Contract for the returned `IO[Unit]` in the provided function:
   *
   *  - can be asynchronous
   *  - can be cancelable, in which case it hooks into IO's cancelation
   *    mechanism such that the resulting task is cancelable
   *  - it should not end in error, because the provided callback
   *    is the only way to signal the final result and it can only
   *    be called once, so invoking it twice would be a contract
   *    violation; so on errors thrown in `IO`, the task can become
   *    non-terminating, with the error being printed to stderr
   *
   * @see [[async]] and [[cancelable]]
   */
  def asyncF[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): IO[A] =
    Async { (conn, cb) =>
      // Must create new connection, otherwise we can have a race
      // condition b/t the bind continuation and `startCancelable` below
      val conn2 = IOConnection()
      conn.push(conn2.cancel)
      // The callback handles "conn.pop()"
      val cb2 = Callback.asyncIdempotent(conn, cb)
      val fa =
        try k(cb2)
        catch { case NonFatal(t) => IO(cb2(Left(t))) }
      IORunLoop.startCancelable(fa, conn2, Callback.report)
    }

  /**
   * Builds a cancelable `IO`.
   *
   * Implements [[Concurrent.cancelable]].
   *
   * The provided function takes a side effectful callback that's
   * supposed to be registered in async apis for signaling a final
   * result.
   *
   * The provided function also returns an `IO[Unit]` that represents
   * the cancelation token, the logic needed for canceling the
   * running computations.
   *
   * Example:
   *
   * {{{
   *   import java.util.concurrent.ScheduledExecutorService
   *   import scala.concurrent.duration._
   *
   *   def sleep(d: FiniteDuration)(implicit ec: ScheduledExecutorService): IO[Unit] =
   *     IO.cancelable { cb =>
   *       // Schedules task to run after delay
   *       val run = new Runnable { def run() = cb(Right(())) }
   *       val future = ec.schedule(run, d.length, d.unit)
   *
   *       // Cancellation logic, suspended in IO
   *       IO(future.cancel(true))
   *     }
   * }}}
   *
   * This example is for didactic purposes, you don't need to describe
   * this function, as it's already available in [[IO.sleep]].
   *
   * @see [[async]] for a simpler variant that builds non-cancelable,
   *      async tasks
   *
   * @see [[asyncF]] for a more potent version that does hook into
   *      the underlying cancelation model
   */
  def cancelable[A](k: (Either[Throwable, A] => Unit) => CancelToken[IO]): IO[A] =
    Async { (conn, cb) =>
      val cb2 = Callback.asyncIdempotent(conn, cb)
      val ref = ForwardCancelable()
      conn.push(ref.cancel)
      // Race condition test — no need to execute `k` if it was already cancelled,
      // ensures that fiber.cancel will always wait for the finalizer if `k`
      // is executed — note that `isCanceled` is visible here due to `push`
      if (!conn.isCanceled)
        ref.complete(
          try k(cb2)
          catch {
            case NonFatal(t) =>
              cb2(Left(t))
              IO.unit
          }
        )
      else
        ref.complete(IO.unit)
    }

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
  def raiseError[A](e: Throwable): IO[A] = RaiseError(e)

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
  def fromFuture[A](iof: IO[Future[A]])(implicit cs: ContextShift[IO]): IO[A] =
    iof.flatMap(IOFromFuture.apply).guarantee(cs.shift)

  @deprecated("Use the variant that takes an implicit ContextShift.", "2.0.0")
  private[IO] def fromFuture[A](iof: IO[Future[A]]): IO[A] =
    iof.flatMap(IOFromFuture.apply)

  /**
   * Lifts an `Either[Throwable, A]` into the `IO[A]` context, raising
   * the throwable if it exists.
   */
  def fromEither[A](e: Either[Throwable, A]): IO[A] =
    e match {
      case Right(a)  => pure(a)
      case Left(err) => raiseError(err)
    }

  /**
   * Lifts an `Option[A]` into the `IO[A]` context, raising the throwable if the option is empty.
   */
  def fromOption[A](option: Option[A])(orElse: => Throwable): IO[A] = option match {
    case None        => raiseError(orElse)
    case Some(value) => pure(value)
  }

  /**
   * Lifts an `Try[A]` into the `IO[A]` context, raising the throwable if it
   * exists.
   */
  def fromTry[A](t: Try[A]): IO[A] =
    t match {
      case Success(a)   => pure(a)
      case Failure(err) => raiseError(err)
    }

  /**
   * Asynchronous boundary described as an effectful `IO`, managed
   * by the provided [[ContextShift]].
   *
   * $shiftDesc
   *
   * @param cs is the [[ContextShift]] that's managing the thread-pool
   *        used to trigger this async boundary
   */
  def shift(implicit cs: ContextShift[IO]): IO[Unit] =
    cs.shift

  /**
   * Asynchronous boundary described as an effectful `IO`, managed
   * by the provided Scala `ExecutionContext`.
   *
   * $shiftDesc
   *
   * @param ec is the Scala `ExecutionContext` that's managing the
   *        thread-pool used to trigger this async boundary
   */
  def shift(ec: ExecutionContext): IO[Unit] =
    IOShift(ec)

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
   * Similar with [[IO.shift(implicit* IO.shift]], you can combine it
   * via `flatMap` to create delayed tasks:
   *
   * {{{
   *   val timeout = IO.sleep(10.seconds).flatMap { _ =>
   *     IO.raiseError(new TimeoutException)
   *   }
   * }}}
   *
   * This operation creates an asynchronous boundary, even if the
   * specified duration is zero, so you can count on this equivalence:
   *
   * {{{
   *   IO.sleep(Duration.Zero) <-> IO.shift
   * }}}
   *
   * The created task is cancelable and so it can be used safely in race
   * conditions without resource leakage.
   *
   * @param duration is the time span to wait before emitting the tick
   *
   * @param timer is the [[Timer]] used to manage this delayed task,
   *        `IO.sleep` being in fact just an alias for [[Timer.sleep]]
   *
   * @return a new asynchronous and cancelable `IO` that will sleep for
   *         the specified duration and then finally emit a tick
   */
  def sleep(duration: FiniteDuration)(implicit timer: Timer[IO]): IO[Unit] =
    timer.sleep(duration)

  /**
   * Returns a cancelable boundary — an `IO` task that checks for the
   * cancellation status of the run-loop and does not allow for the
   * bind continuation to keep executing in case cancellation happened.
   *
   * This operation is very similar to [[IO.shift(implicit* IO.shift]],
   * as it can be dropped in `flatMap` chains in order to make loops
   * cancelable.
   *
   * Example:
   *
   * {{{
   *  def fib(n: Int, a: Long, b: Long): IO[Long] =
   *    IO.suspend {
   *      if (n <= 0) IO.pure(a) else {
   *        val next = fib(n - 1, b, a + b)
   *
   *        // Every 100-th cycle, check cancellation status
   *        if (n % 100 == 0)
   *          IO.cancelBoundary *> next
   *        else
   *          next
   *      }
   *    }
   * }}}
   */
  val cancelBoundary: IO[Unit] = {
    val start: Start[Unit] = (_, cb) => cb(Callback.rightUnit)
    Async(start, trampolineAfter = true)
  }

  /**
   * Run two IO tasks concurrently, and return the first to
   * finish, either in success or error. The loser of the race is
   * canceled.
   *
   * The two tasks are executed in parallel if asynchronous,
   * the winner being the first that signals a result.
   *
   * As an example see [[IO.timeout]] and [[IO.timeoutTo]]
   *
   * N.B. this is the implementation of [[Concurrent.race]].
   *
   * Also see [[racePair]] for a version that does not cancel
   * the loser automatically on successful results.
   *
   * @param lh is the "left" task participating in the race
   * @param rh is the "right" task participating in the race
   *
   * @param cs is an implicit requirement needed because
   *        `race` automatically forks the involved tasks
   */
  def race[A, B](lh: IO[A], rh: IO[B])(implicit cs: ContextShift[IO]): IO[Either[A, B]] =
    IORace.simple(cs, lh, rh)

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
   * N.B. this is the implementation of [[Concurrent.racePair]].
   *
   * See [[race]] for a simpler version that cancels the loser
   * immediately.
   *
   * @param lh is the "left" task participating in the race
   * @param rh is the "right" task participating in the race
   *
   * @param cs is an implicit requirement needed because
   *        `race` automatically forks the involved tasks
   */
  def racePair[A, B](
    lh: IO[A],
    rh: IO[B]
  )(implicit cs: ContextShift[IO]): IO[Either[(A, Fiber[IO, B]), (Fiber[IO, A], B)]] =
    IORace.pair(cs, lh, rh)

  /**
   * Returns a [[ContextShift]] instance for [[IO]], built from a
   * Scala `ExecutionContext`.
   *
   * NOTE: you don't need to build such instances when using [[IOApp]].
   *
   * @param ec is the execution context used for the actual execution of
   *        tasks (e.g. bind continuations) and can be backed by the
   *        user's own thread-pool
   */
  def contextShift(ec: ExecutionContext): ContextShift[IO] =
    IOContextShift(ec)

  /* -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-= */
  /* IO's internal encoding: */

  /** Corresponds to [[IO.pure]]. */
  final private[effect] case class Pure[+A](a: A) extends IO[A]

  /** Corresponds to [[IO.apply]]. */
  final private[effect] case class Delay[+A](thunk: () => A) extends IO[A]

  /** Corresponds to [[IO.raiseError]]. */
  final private[effect] case class RaiseError(e: Throwable) extends IO[Nothing]

  /** Corresponds to [[IO.suspend]]. */
  final private[effect] case class Suspend[+A](thunk: () => IO[A]) extends IO[A]

  /** Corresponds to [[IO.flatMap]]. */
  final private[effect] case class Bind[E, +A](source: IO[E], f: E => IO[A]) extends IO[A]

  /** Corresponds to [[IO.map]]. */
  final private[effect] case class Map[E, +A](source: IO[E], f: E => A, index: Int) extends IO[A] with (E => IO[A]) {
    override def apply(value: E): IO[A] =
      new Pure(f(value))
  }

  /**
   * Corresponds to [[IO.async]] and other async definitions
   * (e.g. [[IO.race]], [[IO.shift]], etc)
   *
   * @param k is the "registration" function that starts the
   *        async process — receives an [[internals.IOConnection]]
   *        that keeps cancellation tokens.
   *
   * @param trampolineAfter is `true` if the
   *        [[cats.effect.internals.IORunLoop.RestartCallback]]
   *        should introduce a trampolined async boundary
   *        on calling the callback for transmitting the
   *        signal downstream
   */
  final private[effect] case class Async[+A](
    k: (IOConnection, Either[Throwable, A] => Unit) => Unit,
    trampolineAfter: Boolean = false
  ) extends IO[A]

  /**
   * An internal state for that optimizes changes to
   * [[internals.IOConnection]].
   *
   * [[IO.uncancelable]] is optimized via `ContextSwitch`
   * and we could express `bracket` in terms of it as well.
   */
  final private[effect] case class ContextSwitch[A](
    source: IO[A],
    modify: IOConnection => IOConnection,
    restore: (A, Throwable, IOConnection, IOConnection) => IOConnection
  ) extends IO[A]

  /* -=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-=-= */

  /**
   * Internal reference, used as an optimization for [[IO.attempt]]
   * in order to avoid extraneous memory allocations.
   */
  private object AttemptIO extends IOFrame[Any, IO[Either[Throwable, Any]]] {
    override def apply(a: Any) =
      Pure(Right(a))
    override def recover(e: Throwable) =
      Pure(Left(e))
  }
}
