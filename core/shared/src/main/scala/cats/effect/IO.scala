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

import cats.arrow.FunctionK
import cats.effect.internals.IOFrame.ErrorHandler
import cats.effect.internals._
import cats.effect.internals.IOPlatform.fusionMaxStackDepth

import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.concurrent.duration._
import scala.util.{Left, Right}

/**
 * A pure abstraction representing the intention to perform a
 * side effect, where the result of that side effect may be obtained
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
   * Produces an `IO` reference that is guaranteed to be safe to run
   * synchronously (i.e. [[unsafeRunSync]]), being the safe analogue
   * to [[unsafeRunAsync]].
   *
   * This operation is isomorphic to [[unsafeRunAsync]]. What it does
   * is to let you describe asynchronous execution with a function
   * that stores off the results of the original `IO` as a
   * side effect, thus ''avoiding'' the usage of impure callbacks or
   * eager evaluation.
   */
  final def runAsync(cb: Either[Throwable, A] => IO[Unit]): IO[Unit] = IO {
    unsafeRunAsync(cb.andThen(_.unsafeRunAsync(_ => ())))
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
   * Converts the source `IO` into any `F` type that implements
   * the [[cats.effect.Async Async]] type class.
   */
  final def to[F[_]](implicit F: cats.effect.Async[F]): F[A @uncheckedVariance] =
    this match {
      case Pure(a) => F.pure(a)
      case Delay(thunk) => F.delay(thunk())
      case RaiseError(e) => F.raiseError(e)
      case Suspend(thunk) => F.suspend(thunk().to[F])
      case Async(k) => F.async(k)
      case Bind(source, frame) =>
        frame match {
          case m: IOFrame[_, _] =>
            if (!m.isInstanceOf[ErrorHandler[_]]) {
              val lh = F.attempt(F.suspend(source.to[F]))
              val f = m.asInstanceOf[IOFrame[Any, IO[A]]]
              F.flatMap(lh)(e => f.fold(e).to[F])
            } else {
              val lh = F.suspend(source.to[F]).asInstanceOf[F[A]]
              F.handleErrorWith(lh) { e =>
                m.asInstanceOf[ErrorHandler[A]].recover(e).to[F]
              }
            }
          case f =>
            F.flatMap(F.suspend(source.to[F]))(e => f(e).to[F])
        }
      case Map(source, f, _) =>
        F.map(source.to[F])(f.asInstanceOf[Any => A])
    }

  override def toString = this match {
    case Pure(a) => s"IO($a)"
    case RaiseError(e) => s"IO(throw $e)"
    case _ => "IO$" + System.identityHashCode(this)
  }
}

private[effect] abstract class IOParallelNewtype {
  /** Newtype encoding for an `IO` datatype that has a `cats.Applicative`
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

  /** Newtype encoding, see the [[IO.Par]] type alias
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

  implicit val ioEffect: Effect[IO] = new Effect[IO] {
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
      IO.Bind(ioa, IOFrame.errorHandler(f))
    override def raiseError[A](e: Throwable): IO[A] =
      IO.raiseError(e)
    override def suspend[A](thunk: => IO[A]): IO[A] =
      IO.suspend(thunk)
    override def async[A](k: (Either[Throwable, A] => Unit) => Unit): IO[A] =
      IO.async(k)
    override def runAsync[A](ioa: IO[A])(cb: Either[Throwable, A] => IO[Unit]): IO[Unit] =
      ioa.runAsync(cb)
    // creates a new call-site, so *very* slightly faster than using the default
    override def shift(implicit ec: ExecutionContext): IO[Unit] =
      IO.shift(ec)
    override def liftIO[A](ioa: IO[A]): IO[A] =
      ioa

    // this will use stack proportional to the maximum number of joined async suspensions
    override def tailRecM[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
      f(a) flatMap {
        case Left(a) => tailRecM(a)(f)
        case Right(b) => pure(b)
      }
  }

  implicit val ioParallel: Parallel[IO, IO.Par] =
    new Parallel[IO, IO.Par] {
      override def applicative: Applicative[IO.Par] =
        parApplicative
      override def monad: Monad[IO] =
        ioEffect
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
    Async { cb =>
      val cb2 = IOPlatform.onceOnly(cb)
      try k(cb2) catch { case NonFatal(t) => cb2(Left(t)) }
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
   * Note that the ''continuation'' of the computation resulting from
   * a `Future` will run on the future's thread pool.  There is no
   * thread shifting here; the `ExecutionContext` is solely for the
   * benefit of the `Future`.
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
  def fromFuture[A](iof: IO[Future[A]])(implicit ec: ExecutionContext): IO[A] = iof flatMap { f =>
    IO async { cb =>
      import scala.util.{Success, Failure}

      f onComplete {
        case Failure(e) => cb(Left(e))
        case Success(a) => cb(Right(a))
      }
    }
  }

  /**
   * Lifts an Either[Throwable, A] into the IO[A] context raising the throwable
   * if it exists.
   */
  def fromEither[A](e: Either[Throwable, A]): IO[A] = e.fold(IO.raiseError, IO.pure)

  /**
   * Shifts the bind continuation of the `IO` onto the specified thread
   * pool.
   *
   * Asynchronous actions cannot be shifted, since they are scheduled
   * rather than run. Also, no effort is made to re-shift synchronous
   * actions which *follow* asynchronous actions within a bind chain;
   * those actions will remain on the continuation thread inherited
   * from their preceding async action.  The only computations which
   * are shifted are those which are defined as synchronous actions and
   * are contiguous in the bind chain ''following'' the `shift`.
   *
   * As an example:
   *
   * {{{
   * for {
   *   _ <- IO.shift(BlockingIO)
   *   bytes <- readFileUsingJavaIO(file)
   *   _ <- IO.shift(DefaultPool)
   *
   *   secure = encrypt(bytes, KeyManager)
   *   _ <- sendResponse(Protocol.v1, secure)
   *
   *   _ <- IO { println("it worked!") }
   * } yield ()
   * }}}
   *
   * In the above, `readFileUsingJavaIO` will be shifted to the pool
   * represented by `BlockingIO`, so long as it is defined using `apply`
   * or `suspend` (which, judging by the name, it probably is).  Once
   * its computation is complete, the rest of the `for`-comprehension is
   * shifted ''again'', this time onto the `DefaultPool`.  This pool is
   * used to compute the encrypted version of the bytes, which are then
   * passed to `sendResponse`.  If we assume that `sendResponse` is
   * defined using `async` (perhaps backed by an NIO socket channel),
   * then we don't actually know on which pool the final `IO` action (the
   * `println`) will be run.  If we wanted to ensure that the `println`
   * runs on `DefaultPool`, we would insert another `shift` following
   * `sendResponse`.
   *
   * Another somewhat less common application of `shift` is to reset the
   * thread stack and yield control back to the underlying pool.  For
   * example:
   *
   * {{{
   * lazy val repeat: IO[Unit] = for {
   *   _ <- doStuff
   *   _ <- IO.shift
   *   _ <- repeat
   * } yield ()
   * }}}
   *
   * In this example, `repeat` is a very long running `IO` (infinite, in
   * fact!) which will just hog the underlying thread resource for as long
   * as it continues running.  This can be a bit of a problem, and so we
   * inject the `IO.shift` which yields control back to the underlying
   * thread pool, giving it a chance to reschedule things and provide
   * better fairness.  This shifting also "bounces" the thread stack, popping
   * all the way back to the thread pool and effectively trampolining the
   * remainder of the computation.  This sort of manual trampolining is
   * unnecessary if `doStuff` is defined using `suspend` or `apply`, but if
   * it was defined using `async` and does ''not'' involve any real
   * concurrency, the call to `shift` will be necessary to avoid a
   * `StackOverflowError`.
   *
   * Thus, this function has four important use cases: shifting blocking
   * actions off of the main compute pool, defensively re-shifting
   * asynchronous continuations back to the main compute pool, yielding
   * control to some underlying pool for fairness reasons, and preventing
   * an overflow of the call stack in the case of improperly constructed
   * `async` actions.
   */
  def shift(implicit ec: ExecutionContext): IO[Unit] = {
    IO async { (cb: Either[Throwable, Unit] => Unit) =>
      ec.execute(new Runnable {
        def run() = cb(Right(()))
      })
    }
  }

  private[effect] final case class Pure[+A](a: A)
    extends IO[A]
  private[effect] final case class Delay[+A](thunk: () => A)
    extends IO[A]
  private[effect] final case class RaiseError(e: Throwable)
    extends IO[Nothing]
  private[effect] final case class Suspend[+A](thunk: () => IO[A])
    extends IO[A]
  private[effect] final case class Async[+A](k: (Either[Throwable, A] => Unit) => Unit)
    extends IO[A]
  private[effect] final case class Bind[E, +A](source: IO[E], f: E => IO[A])
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
