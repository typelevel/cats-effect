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

import cats.arrow.FunctionK
import cats.effect.internals._
import cats.effect.internals.Callback.Extensions
import cats.effect.internals.TrampolineEC.immediate
import cats.effect.internals.IOPlatform.fusionMaxStackDepth

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Left, Right, Success}

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
   * Functor map on `IO`. Given a mapping functions, it transforms the
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
  final def runAsync(cb: Either[Throwable, A] => IO[Unit]): IO[Unit] = IO {
    unsafeRunAsync(cb.andThen(_.unsafeRunAsync(Callback.report)))
  }

  final def runSyncStep: IO[Either[IO[A], A]] = IO.suspend {
    IORunLoop.step(this) match {
      case Pure(a) => Pure(Right(a))
      case r @ RaiseError(_) => r
      case async => Pure(Left(async))
    }
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
   *   val start: IO[IO[Unit]] = source.runCancelable
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
  final def runCancelable(cb: Either[Throwable, A] => IO[Unit]): IO[IO[Unit]] = IO {
    val cancel = unsafeRunCancelable(cb.andThen(_.unsafeRunAsync(_ => ())))
    IO.Delay(cancel)
  }

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
  final def unsafeRunCancelable(cb: Either[Throwable, A] => Unit): () => Unit = {
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
   */
  final def unsafeRunTimed(limit: Duration): Option[A] =
    IORunLoop.step(this) match {
      case Pure(a) => Some(a)
      case RaiseError(e) => throw e
      case self @ Async(_) =>
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
    unsafeRunAsync(_.fold(p.failure, p.success))
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
   * IMPORTANT — this operation does not start with an asynchronous boundary.
   * But you can use [[IO.shift(implicit* IO.shift]] to force an async
   * boundary just before start.
   */
  final def start: IO[Fiber[IO, A @uncheckedVariance]] =
    IOStart(this)

  /**
   * Returns a new `IO` that mirrors the source task for normal termination,
   * but that triggers the given error on cancellation.
   *
   * Normally tasks that are canceled become non-terminating.
   *
   * This `onCancelRaiseError` operator transforms a task that is
   * non-terminating on cancellation into one that yields an error,
   * thus equivalent with [[IO.raiseError]].
   */
  final def onCancelRaiseError(e: Throwable): IO[A] =
    IOCancel.raise(this, e)

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
   *        canceled, receiving as input the resource that needs that
   *        needs release, along with the result of `use`
   *        (cancellation, error or successful result)
   */
  def bracketCase[B](use: A => IO[B])(release: (A, ExitCase[Throwable]) => IO[Unit]): IO[B] =
    IOBracket(this)(use)(release)

  /**
   * Handle any error, potentially recovering from it, by mapping it to another
   * `IO` value.
   *
   * Implements `ApplicativeError.handleErrorWith`.
   */
  def handleErrorWith[AA >: A](f: Throwable => IO[AA]): IO[AA] =
    IO.Bind(this, new IOFrame.ErrorHandler(f))

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
    case Pure(a) => s"IO($a)"
    case RaiseError(e) => s"IO(throw $e)"
    case _ => "IO$" + System.identityHashCode(this)
  }
}

private[effect] abstract class IOParallelNewtype
  extends internals.IOTimerRef with internals.IOCompanionBinaryCompat {

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
}

private[effect] abstract class IOLowPriorityInstances extends IOParallelNewtype {
  private[effect] class IOSemigroup[A: Semigroup] extends Semigroup[IO[A]] {
    def combine(ioa1: IO[A], ioa2: IO[A]) =
      ioa1.flatMap(a1 => ioa2.map(a2 => Semigroup[A].combine(a1, a2)))
  }

  implicit def ioSemigroup[A: Semigroup]: Semigroup[IO[A]] = new IOSemigroup[A]
}

private[effect] abstract class IOInstances extends IOLowPriorityInstances {

  implicit val parApplicative: Applicative[IO.Par] = new Applicative[IO.Par] {
    import IO.Par.unwrap
    import IO.Par.{apply => par}

    override def pure[A](x: A): IO.Par[A] =
      par(IO.pure(x))
    override def map2[A, B, Z](fa: IO.Par[A], fb: IO.Par[B])(f: (A, B) => Z): IO.Par[Z] =
      par(IOParMap(unwrap(fa), unwrap(fb))(f))
    override def ap[A, B](ff: IO.Par[A => B])(fa: IO.Par[A]): IO.Par[B] =
      map2(ff, fa)(_(_))
    override def product[A, B](fa: IO.Par[A], fb: IO.Par[B]): IO.Par[(A, B)] =
      map2(fa, fb)((_, _))
    override def map[A, B](fa: IO.Par[A])(f: A => B): IO.Par[B] =
      par(unwrap(fa).map(f))
    override def unit: IO.Par[Unit] =
      par(IO.unit)
  }

  implicit val ioConcurrentEffect: ConcurrentEffect[IO] = new ConcurrentEffect[IO] {
    override def pure[A](a: A): IO[A] =
      IO.pure(a)
    override def flatMap[A, B](ioa: IO[A])(f: A => IO[B]): IO[B] =
      ioa.flatMap(f)
    override def map[A, B](fa: IO[A])(f: A => B): IO[B] =
      fa.map(f)
    override def delay[A](thunk: => A): IO[A] =
      IO(thunk)
    override def unit: IO[Unit] =
      IO.unit
    override def attempt[A](ioa: IO[A]): IO[Either[Throwable, A]] =
      ioa.attempt
    override def handleErrorWith[A](ioa: IO[A])(f: Throwable => IO[A]): IO[A] =
      ioa.handleErrorWith(f)
    override def raiseError[A](e: Throwable): IO[A] =
      IO.raiseError(e)
    override def suspend[A](thunk: => IO[A]): IO[A] =
      IO.suspend(thunk)
    override def start[A](fa: IO[A]): IO[Fiber[IO, A]] =
      fa.start
    override def uncancelable[A](fa: IO[A]): IO[A] =
      fa.uncancelable
    override def onCancelRaiseError[A](fa: IO[A], e: Throwable): IO[A] =
      fa.onCancelRaiseError(e)
    override def async[A](k: (Either[Throwable, A] => Unit) => Unit): IO[A] =
      IO.async(k)
    override def race[A, B](fa: IO[A], fb: IO[B]): IO[Either[A, B]] =
      IO.race(fa, fb)
    override def racePair[A, B](fa: IO[A], fb: IO[B]): IO[Either[(A, Fiber[IO, B]), (Fiber[IO, A], B)]] =
      IO.racePair(fa, fb)
    override def runAsync[A](ioa: IO[A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit] =
      ioa.runAsync(cb)
    override def runSyncStep[A](ioa: IO[A]): IO[Either[IO[A], A]] =
      ioa.runSyncStep
    override def cancelable[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): IO[A] =
      IO.cancelable(k)
    override def runCancelable[A](fa: IO[A])(cb: Either[Throwable, A] => IO[Unit]): IO[IO[Unit]] =
      fa.runCancelable(cb)
    override def liftIO[A](ioa: IO[A]): IO[A] =
      ioa
    // this will use stack proportional to the maximum number of joined async suspensions
    override def tailRecM[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
      f(a).flatMap {
        case Left(a) => tailRecM(a)(f)
        case Right(b) => pure(b)
      }
    override def bracket[A, B](acquire: IO[A])
      (use: A => IO[B])
      (release: A => IO[Unit]): IO[B] =
      acquire.bracket(use)(release)
    override def bracketCase[A, B](acquire: IO[A])
      (use: A => IO[B])
      (release: (A, ExitCase[Throwable]) => IO[Unit]): IO[B] =
      acquire.bracketCase(use)(release)
  }

  implicit val ioParallel: Parallel[IO, IO.Par] =
    new Parallel[IO, IO.Par] {
      override def applicative: Applicative[IO.Par] =
        parApplicative
      override def monad: Monad[IO] =
        ioConcurrentEffect
      override val sequential: ~>[IO.Par, IO] =
        new FunctionK[IO.Par, IO] { def apply[A](fa: IO.Par[A]): IO[A] = IO.Par.unwrap(fa) }
      override val parallel: ~>[IO, IO.Par] =
        new FunctionK[IO, IO.Par] { def apply[A](fa: IO[A]): IO.Par[A] = IO.Par(fa) }
    }

  implicit def ioMonoid[A: Monoid]: Monoid[IO[A]] = new IOSemigroup[A] with Monoid[IO[A]] {
    def empty = IO.pure(Monoid[A].empty)
  }

  implicit val ioSemigroupK: SemigroupK[IO] = new SemigroupK[IO] {
    def combineK[A](a: IO[A], b: IO[A]): IO[A] =
      ApplicativeError[IO, Throwable].handleErrorWith(a)(_ => b)
  }
}

/**
 * @define shiftDesc For example we can introduce an asynchronous
 *         boundary in the `flatMap` chain before a certain task:
 *         {{{
 *           IO.shift.flatMap(_ => task)
 *         }}}
 *
 *         Or using Cats syntax:
 *         {{{
 *           import cats.syntax.all._
 *
 *           IO.shift *> task
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
 *         Note there are 2 overloads of this function:
 *
 *          - one that takes an [[Timer]] ([[IO.shift(implicit* link]])
 *          - one that takes a Scala `ExecutionContext` ([[IO.shift(ec* link]])
 *
 *         Use the former by default, use the later for fine grained
 *         control over the thread pool used.
 */
object IO extends IOInstances {

  /**
   * Suspends a synchronous side effect in `IO`.
   *
   * Any exceptions thrown by the effect will be caught and sequenced
   * into the `IO`.
   */
  def apply[A](body: => A): IO[A] = Delay(body _)

  /**
   * Suspends a synchronous side effect which produces an `IO` in `IO`.
   *
   * This is useful for trampolining (i.e. when the side effect is
   * conceptually the allocation of a stack frame).  Any exceptions
   * thrown by the side effect will be caught and sequenced into the
   * `IO`.
   */
  def suspend[A](thunk: => IO[A]): IO[A] =
    Suspend(thunk _)

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
   */
  def async[A](k: (Either[Throwable, A] => Unit) => Unit): IO[A] = {
    Async { (_, cb) =>
      val cb2 = Callback.asyncIdempotent(null, cb)
      try k(cb2) catch { case NonFatal(t) => cb2(Left(t)) }
    }
  }

  /**
   * Builds a cancelable `IO`.
   */
  def cancelable[A](k: (Either[Throwable, A] => Unit) => IO[Unit]): IO[A] =
    Async { (conn, cb) =>
      val cb2 = Callback.asyncIdempotent(conn, cb)
      val ref = ForwardCancelable()
      conn.push(ref)

      ref := (
        try k(cb2) catch { case NonFatal(t) =>
          cb2(Left(t))
          IO.unit
        })
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
  def fromFuture[A](iof: IO[Future[A]]): IO[A] =
    iof.flatMap { f =>
      IO.async { cb =>
        f.onComplete(r => cb(r match {
          case Success(a) => Right(a)
          case Failure(e) => Left(e)
        }))(immediate)
      }
    }

  /**
   * Lifts an Either[Throwable, A] into the IO[A] context raising the throwable
   * if it exists.
   */
  def fromEither[A](e: Either[Throwable, A]): IO[A] =
    e match {
      case Right(a) => pure(a)
      case Left(err) => raiseError(err)
    }

  /**
   * Asynchronous boundary described as an effectful `IO`, managed
   * by the provided [[Timer]].
   *
   * This operation can be used in `flatMap` chains to "shift" the
   * continuation of the run-loop to another thread or call stack.
   *
   * $shiftDesc
   *
   * @param timer is the [[Timer]] that's managing the thread-pool
   *        used to trigger this async boundary
   */
  def shift(implicit timer: Timer[IO]): IO[Unit] =
    timer.shift

  /**
   * Asynchronous boundary described as an effectful `IO`, managed
   * by the provided Scala `ExecutionContext`.
   *
   * This operation can be used in `flatMap` chains to "shift" the
   * continuation of the run-loop to another thread or call stack.
   *
   * $shiftDesc
   *
   * @param ec is the Scala `ExecutionContext` that's managing the
   *        thread-pool used to trigger this async boundary
   */
  def shift(ec: ExecutionContext): IO[Unit] = {
    IO.Async { (_, cb: Either[Throwable, Unit] => Unit) =>
      ec.execute(new Runnable {
        def run() = cb(Callback.rightUnit)
      })
    }
  }

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
    IO.Async { (conn, cb) =>
      if (!conn.isCanceled)
        cb.async(Callback.rightUnit)
    }
  }

  /**
   * Run two IO tasks concurrently, and return the first to
   * finish, either in success or error. The loser of the race is
   * canceled.
   *
   * The two tasks are executed in parallel if asynchronous,
   * the winner being the first that signals a result.
   *
   * As an example, this is how a `timeout` operation could be
   * implemented in terms of `race`:
   *
   * {{{
   *   import cats.effect._
   *   import scala.concurrent.duration._
   *
   *   def timeoutTo[A](io: IO[A], after: FiniteDuration, fallback: IO[A])
   *     (implicit timer: Timer[IO]): IO[A] = {
   *
   *     IO.race(io, timer.sleep(after)).flatMap {
   *       case Left((a, _)) => IO.pure(a)
   *       case Right((_, _)) => fallback
   *     }
   *   }
   *
   *   def timeout[A](io: IO[A], after: FiniteDuration)
   *     (implicit timer: Timer[IO]): IO[A] = {
   *
   *     timeoutTo(io, after,
   *       IO.raiseError(new TimeoutException(after.toString)))
   *   }
   * }}}
   *
   * N.B. this is the implementation of [[Concurrent.race]].
   *
   * Also see [[racePair]] for a version that does not cancel
   * the loser automatically on successful results.
   */
  def race[A, B](lh: IO[A], rh: IO[B]): IO[Either[A, B]] =
    IORace.simple(lh, rh)

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
   */
  def racePair[A, B](lh: IO[A], rh: IO[B]): IO[Either[(A, Fiber[IO, B]), (Fiber[IO, A], B)]] =
    IORace.pair(lh, rh)

  private[effect] final case class Pure[+A](a: A)
    extends IO[A]
  private[effect] final case class Delay[+A](thunk: () => A)
    extends IO[A]
  private[effect] final case class RaiseError(e: Throwable)
    extends IO[Nothing]
  private[effect] final case class Suspend[+A](thunk: () => IO[A])
    extends IO[A]
  private[effect] final case class Bind[E, +A](source: IO[E], f: E => IO[A])
    extends IO[A]
  private[effect] final case class Async[+A](
    k: (IOConnection, Either[Throwable, A] => Unit) => Unit)
    extends IO[A]

  /** State for representing `map` ops that itself is a function in
   * order to avoid extraneous memory allocations when building the
   * internal call-stack.
   */
  private[effect] final case class Map[E, +A](source: IO[E], f: E => A, index: Int)
    extends IO[A] with (E => IO[A]) {

    override def apply(value: E): IO[A] =
      IO.pure(f(value))
  }

  /** Internal reference, used as an optimization for [[IO.attempt]]
   * in order to avoid extraneous memory allocations.
   */
  private object AttemptIO extends IOFrame[Any, IO[Either[Throwable, Any]]] {
    override def apply(a: Any) =
      Pure(Right(a))
    override def recover(e: Throwable) =
      Pure(Left(e))
  }
}
