/*
 * Copyright 2017 Typelevel
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

import cats.effect.internals.{AndThen, IOPlatform, NonFatal}
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Left, Right}

/**
 * A pure abstraction representing the intention to perform a
 * side-effect, where the result of that side-effect may be obtained
 * synchronously (via return) or asynchronously (via callback).
 *
 * Effects contained within this abstraction are not evaluated until
 * the "end of the world", which is to say, when one of the "unsafe"
 * methods are used.  Effectful results are not memoized, meaning that
 * memory overhead is minimal (and no leaks), and also that a single
 * effect may be run multiple times in a referentially-transparent
 * manner.  For example:
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
 * `IO` is trampolined for all ''synchronous'' joins.  This means that
 * you can safely call `flatMap` in a recursive function of arbitrary
 * depth, without fear of blowing the stack.  However, `IO` cannot
 * guarantee stack-safety in the presence of arbitrarily nested
 * asynchronous suspensions.  This is quite simply because it is
 * ''impossible'' (on the JVM) to guarantee stack-safety in that case.
 * For example:
 *
 * {{{
 * def lie[A]: IO[A] = IO.async(cb => cb(Right(lie))).flatMap(a => a)
 * }}}
 *
 * This should blow the stack when evaluated. Also note that there is
 * no way to encode this using `tailRecM` in such a way that it does
 * ''not'' blow the stack.  Thus, the `tailRecM` on `Monad[IO]` is not
 * guaranteed to produce an `IO` which is stack-safe when run, but
 * will rather make every attempt to do so barring pathological
 * structure.
 *
 * `IO` makes no attempt to control finalization or guaranteed
 * resource-safety in the presence of concurrent preemption, simply
 * because `IO` does not care about concurrent preemption at all!
 * `IO` actions are not interruptible and should be considered
 * broadly-speaking atomic, at least when used purely.
 */
sealed abstract class IO[+A] {
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
  final def map[B](f: A => B): IO[B] = this match {
    case Pure(a) => try Pure(f(a)) catch { case NonFatal(t) => Fail(t) }
    case Fail(t) => Fail(t)
    case _ => flatMap(f.andThen(Pure(_)))
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
    flatMapTotal(AndThen(a => try f(a) catch { case NonFatal(t) => IO.raiseError(t) }))

  private final def flatMapTotal[B](f: AndThen[A, IO[B]]): IO[B] = {
    this match {
      case Pure(a) => Suspend(AndThen((_: Unit) => a).andThen(f))
      case Fail(t) => Fail(t)
      case Suspend(thunk) => BindSuspend(thunk, f)
      case BindSuspend(thunk, g) => BindSuspend(thunk, g.andThen(AndThen(_.flatMapTotal(f))))
      case Async(k) => BindAsync(k, f)
      case BindAsync(k, g) => BindAsync(k, g.andThen(AndThen(_.flatMapTotal(f))))
    }
  }

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
  def attempt: IO[Either[Throwable, A]]

  /**
   * Sequences the specified `IO` ensuring evaluation regardless of
   * whether or not the target `IO` raises an exception.
   *
   * Analogous to `finally` in a `try`/`catch`/`finally` block. If an
   * exception is raised by the finalizer, it will be passed sequenced
   * into the resultant. This is true even if the target ''also''
   * raised an exception. It mirrors the semantics of `try`/`finally`
   * on the JVM when you perform similar operations.
   *
   * Example:
   *
   * {{{
   * try throw e1 finally throw e2   // throws e2
   *
   * IO.raiseError(e1).ensuring(IO.raiseError(e2)) === IO.raiseError(e2)
   * }}}
   *
   * This function is distinct from monadic `flatMap` (well, really
   * applicative `apply2`) in that an exception sequenced into a
   * monadic bind chain will short-circuit the chain and the
   * subsequent actions will not be run.
   */
  final def ensuring(finalizer: IO[Any]): IO[A] = {
    attempt flatMap { e =>
      finalizer.flatMap(_ => e.fold(IO.raiseError, IO.pure))
    }
  }

  /**
   * Produces an `IO` reference that is guaranteed to be safe to run
   * synchronously (i.e. [[unsafeRunSync]]), being the safe analogue
   * to [[unsafeRunAsync]].
   *
   * This operation is isomorphic to [[unsafeRunAsync]]. What it does
   * is to let you describe asynchronous execution with a function
   * that stores off the results of the original `IO` as a
   * side-effect, thus ''avoiding'' the usage of impure callbacks or
   * eager evaluation.
   */
  final def runAsync(cb: Either[Throwable, A] => IO[Unit]): IO[Unit] = IO {
    unsafeRunAsync(cb.andThen(_.unsafeRunAsync(_ => ())))
  }

  /**
   * Shifts the synchronous prefixes and continuation of the `IO` onto
   * the specified thread pool.
   *
   * Asynchronous actions cannot be shifted, since they are scheduled
   * rather than run. Also, no effort is made to re-shift synchronous
   * actions which *follow* asynchronous actions within a bind chain;
   * those actions will remain on the continuation thread inherited
   * from their preceding async action.  Critically though,
   * synchronous actions which are bound ''after'' the results of this
   * function will also be shifted onto the pool specified here. Thus,
   * you can think of this function as shifting *before* (the
   * contiguous synchronous prefix) and ''after'' (any continuation of
   * the result).
   *
   * There are two immediately obvious applications to this function.
   * One is to re-shift async actions back to a "main" thread pool.
   * For example, if you create an async action to wrap around some
   * sort of event listener, you probably want to `shift` it
   * immediately to take the continuation off of the event dispatch
   * thread.  Another use-case is to ensure that a blocking
   * synchronous action is taken *off* of the main CPU-bound pool.  A
   * common example here would be any use of the `java.io` package,
   * which is entirely blocking and should never be run on your main
   * CPU-bound pool.
   *
   * Note that this function is idempotent given equal values of `EC`,
   * but only prefix-idempotent given differing `EC` values.  For
   * example:
   *
   * {{{
   * val fioa = IO { File.createTempFile("fubar") }
   *
   * fioa.shift(BlockingIOPool).shift(MainPool)
   * }}}
   *
   * The inner call to `shift` will force the synchronous prefix of
   * `fioa` (which is just the single action) to execute on the
   * `BlockingIOPool` when the `IO` is run, and also ensures that the
   * continuation of this action remains on the `BlockingIOPool`.  The
   * outer `shift` similarly forces the synchronous prefix of the
   * results of the inner `shift` onto the specified pool
   * (`MainPool`), but the results of `shift` have no synchronous
   * prefix, meaning that the "before" part of the outer `shift` is a
   * no-op.  The "after" part is not, however, and will force the
   * continuation of the resulting `IO` back onto the `MainPool`.
   * Which is exactly what you want most of the time with blocking
   * actions of this type.
   */
  final def shift(implicit ec: ExecutionContext): IO[A] = {
    val self = attempt.flatMap { e =>
      IO async { (cb: Either[Throwable, A] => Unit) =>
        ec.execute(new Runnable {
          def run() = cb(e)
        })
      }
    }

    IO async { cb =>
      ec.execute(new Runnable {
        def run() = self.unsafeRunAsync(cb)
      })
    }
  }

  @tailrec
  private final def unsafeStep: IO[A] = this match {
    case Suspend(thunk) => thunk(()).unsafeStep
    case BindSuspend(thunk, f) => thunk(()).flatMapTotal(f).unsafeStep
    case _ => this
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
  final def unsafeRunAsync(cb: Either[Throwable, A] => Unit): Unit = unsafeStep match {
    case Pure(a) => cb(Right(a))
    case Fail(t) => cb(Left(t))
    case Async(k) => k(IOPlatform.onceOnly(cb))

    case ba: BindAsync[e, A] =>
      val cb2 = IOPlatform.onceOnly[Either[Throwable, e]] {
        case Left(t) => cb(Left(t))
        case Right(a) => try ba.f(a).unsafeRunAsync(cb) catch { case NonFatal(t) => cb(Left(t)) }
      }

      ba.k(cb2)

    case _ =>
      throw new AssertionError("unreachable")
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
  final def unsafeRunTimed(limit: Duration): Option[A] = unsafeStep match {
    case Pure(a) => Some(a)
    case Fail(t) => throw t
    case self @ (Async(_) | BindAsync(_, _)) =>
      IOPlatform.unsafeResync(self, limit)
    case _ =>
      throw new AssertionError("unreachable")
  }

  /**
   * Evaluates the effect and produces the result in a `Future`.
   *
   * This is similar to `unsafeRunAsync` in that it evaluates the `IO`
   * as a side-effect in a non-blocking fashion, but uses a `Future`
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

  override def toString = this match {
    case Pure(a) => s"IO($a)"
    case Fail(t) => s"IO(throw $t)"
    case _ => "IO$" + System.identityHashCode(this)
  }
}

private[effect] trait IOLowPriorityInstances {

  private[effect] class IOSemigroup[A: Semigroup] extends Semigroup[IO[A]] {
    def combine(ioa1: IO[A], ioa2: IO[A]) =
      ioa1.flatMap(a1 => ioa2.map(a2 => Semigroup[A].combine(a1, a2)))
  }

  implicit def ioSemigroup[A: Semigroup]: Semigroup[IO[A]] = new IOSemigroup[A]
}

private[effect] trait IOInstances extends IOLowPriorityInstances {

  implicit val ioEffect: Effect[IO] = new Effect[IO] {

    def pure[A](a: A) = IO.pure(a)

    def flatMap[A, B](ioa: IO[A])(f: A => IO[B]): IO[B] = ioa.flatMap(f)

    // this will use stack proportional to the maximum number of joined async suspensions
    def tailRecM[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] = f(a) flatMap {
      case Left(a) => tailRecM(a)(f)
      case Right(b) => pure(b)
    }

    override def attempt[A](ioa: IO[A]): IO[Either[Throwable, A]] = ioa.attempt

    def handleErrorWith[A](ioa: IO[A])(f: Throwable => IO[A]): IO[A] =
      ioa.attempt.flatMap(_.fold(f, pure))

    def raiseError[A](t: Throwable): IO[A] = IO.raiseError(t)

    def suspend[A](thunk: => IO[A]): IO[A] = IO.suspend(thunk)

    def async[A](k: (Either[Throwable, A] => Unit) => Unit): IO[A] = IO.async(k)

    def runAsync[A](ioa: IO[A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit] = ioa.runAsync(cb)

    override def shift[A](ioa: IO[A])(implicit ec: ExecutionContext) = ioa.shift

    def liftIO[A](ioa: IO[A]) = ioa
  }

  implicit def ioMonoid[A: Monoid]: Monoid[IO[A]] = new IOSemigroup[A] with Monoid[IO[A]] {
    def empty = IO.pure(Monoid[A].empty)
  }
}

object IO extends IOInstances {

  /**
   * Suspends a synchronous side-effect in `IO`.
   *
   * Any exceptions thrown by the effect will be caught and sequenced
   * into the `IO`.
   */
  def apply[A](body: => A): IO[A] = suspend(Pure(body))

  /**
   * Suspends a synchronous side-effect which produces an `IO` in `IO`.
   *
   * This is useful for trampolining (i.e. when the side-effect is
   * conceptually the allocation of a stack frame).  Any exceptions
   * thrown by the side-effect will be caught and sequenced into the
   * `IO`.
   */
  def suspend[A](thunk: => IO[A]): IO[A] =
    Suspend(AndThen(_ => try thunk catch { case NonFatal(t) => raiseError(t) }))

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
   * Lifts an `Eval` into `IO`.
   *
   * This function will preserve the evaluation semantics of any
   * actions that are lifted into the pure `IO`.  Eager `Eval`
   * instances will be converted into thunk-less `IO` (i.e. eager
   * `IO`), while lazy eval and memoized will be executed as such.
   */
  def eval[A](effect: Eval[A]): IO[A] = effect match {
    case Now(a) => pure(a)
    case effect => apply(effect.value)
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
    Async { cb =>
      try k(cb) catch { case NonFatal(t) => cb(Left(t)) }
    }
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
  def raiseError(e: Throwable): IO[Nothing] = Fail(e)

  /**
   * Constructs an `IO` which evalutes the thunked `Future` and
   * produces the result (or failure).
   * 
   * Because `Future` eagerly evaluates, as well as because it
   * memoizes, this function takes its parameter lazily.  If this
   * laziness is appropriately threaded back to the definition site of
   * the `Future`, it ensures that the computation is fully managed by
   * `IO` and thus referentially transparent.
   *
   * Note that the ''continuation'' of the computation resulting from
   * a `Future` will run on the future's thread pool.  There is no
   * thread shifting here; the `ExecutionContext` is solely for the
   * benefit of the `Future`.
   *
   * Roughly speaking, the following identities hold:
   *
   * {{{
   * IO.fromFuture(f).unsafeToFuture === f     // true-ish (except for memoization)
   * IO.fromFuture(ioa.unsafeToFuture) === ioa // true!
   * }}}
   *
   * @see [[IO#unsafeToFuture]]
   */
  def fromFuture[A](f: => Future[A])(implicit ec: ExecutionContext): IO[A] = {
    IO async { cb =>
      import scala.util.{Success, Failure}

      f onComplete {
        case Failure(t) => cb(Left(t))
        case Success(a) => cb(Right(a))
      }
    }
  }

  private final case class Pure[+A](a: A) extends IO[A] {
    def attempt = Pure(Right(a))
  }

  private final case class Fail(t: Throwable) extends IO[Nothing] {
    def attempt = Pure(Left(t))
  }

  private final case class Suspend[+A](thunk: AndThen[Unit, IO[A]]) extends IO[A] {
    def attempt = Suspend(thunk.andThen(AndThen(_.attempt)))
  }

  private final case class BindSuspend[E, +A](thunk: AndThen[Unit, IO[E]], f: AndThen[E, IO[A]]) extends IO[A] {
    def attempt: BindSuspend[Either[Throwable, E], Either[Throwable, A]] =
      BindSuspend(thunk.andThen(AndThen(_.attempt)), f.andThen(AndThen(_.attempt)).shortCircuit)
  }

  private final case class Async[+A](k: (Either[Throwable, A] => Unit) => Unit) extends IO[A] {
    def attempt = Async(cb => k(attempt => cb(Right(attempt))))
  }

  private final case class BindAsync[E, +A](k: (Either[Throwable, E] => Unit) => Unit, f: AndThen[E, IO[A]]) extends IO[A] {
    def attempt: BindAsync[Either[Throwable, E], Either[Throwable, A]] =
      BindAsync(k.compose(_.compose(Right(_))), f.andThen(AndThen(_.attempt)).shortCircuit)
  }
}
