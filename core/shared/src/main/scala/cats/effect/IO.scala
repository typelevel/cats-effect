/*
 * Copyright 2020 Typelevel
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
  Applicative,
  Eval,
  Id,
  Monoid,
  Now,
  Parallel,
  Semigroup,
  SemigroupK,
  Show,
  StackSafeMonad,
  Traverse
}
import cats.syntax.all._
import cats.effect.instances.spawn
import cats.effect.std.Console

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

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

sealed abstract class IO[+A] private () extends IOPlatform[A] {

  private[effect] def tag: Byte

  def <*[B](that: IO[B]): IO[A] =
    productL(that)

  def *>[B](that: IO[B]): IO[B] =
    productR(that)

  def >>[B](that: => IO[B]): IO[B] =
    flatMap(_ => that)

  def as[B](b: B): IO[B] =
    map(_ => b)

  def attempt: IO[Either[Throwable, A]] =
    IO.Attempt(this)

  def bothOutcome[B](that: IO[B]): IO[(OutcomeIO[A @uncheckedVariance], OutcomeIO[B])] =
    IO.uncancelable { poll =>
      racePair(that).flatMap {
        case Left((oc, f)) => poll(f.join).onCancel(f.cancel).map((oc, _))
        case Right((f, oc)) => poll(f.join).onCancel(f.cancel).map((_, oc))
      }
    }

  def both[B](that: IO[B]): IO[(A, B)] =
    IO.both(this, that)

  def bracket[B](use: A => IO[B])(release: A => IO[Unit]): IO[B] =
    bracketCase(use)((a, _) => release(a))

  def bracketCase[B](use: A => IO[B])(release: (A, OutcomeIO[B]) => IO[Unit]): IO[B] = {
    def doRelease(a: A, outcome: OutcomeIO[B]): IO[Unit] =
      release(a, outcome).handleErrorWith { t =>
        IO.executionContext.flatMap(ec => IO(ec.reportFailure(t)))
      }

    IO uncancelable { poll =>
      flatMap { a =>
        val finalized = poll(use(a)).onCancel(release(a, Outcome.Canceled()))
        val handled = finalized.onError(e => doRelease(a, Outcome.Errored(e)))
        handled.flatMap(b => doRelease(a, Outcome.Succeeded(IO.pure(b))).as(b))
      }
    }
  }

  def evalOn(ec: ExecutionContext): IO[A] = IO.EvalOn(this, ec)

  def flatMap[B](f: A => IO[B]): IO[B] = IO.FlatMap(this, f)

  def flatten[B](implicit ev: A <:< IO[B]): IO[B] = flatMap(ev)

  def guarantee(finalizer: IO[Unit]): IO[A] =
    guaranteeCase(_ => finalizer)

  def guaranteeCase(finalizer: OutcomeIO[A @uncheckedVariance] => IO[Unit]): IO[A] =
    onCase { case oc => finalizer(oc) }

  def handleErrorWith[B >: A](f: Throwable => IO[B]): IO[B] =
    IO.HandleErrorWith(this, f)

  def map[B](f: A => B): IO[B] = IO.Map(this, f)

  def onCancel(fin: IO[Unit]): IO[A] =
    IO.OnCancel(this, fin)

  def onCase(pf: PartialFunction[OutcomeIO[A @uncheckedVariance], IO[Unit]]): IO[A] = {
    def doOutcome(outcome: OutcomeIO[A]): IO[Unit] =
      pf.lift(outcome)
        .fold(IO.unit)(_.handleErrorWith { t =>
          IO.executionContext.flatMap(ec => IO(ec.reportFailure(t)))
        })

    IO uncancelable { poll =>
      val base = poll(this)
      val finalized = pf.lift(Outcome.Canceled()).map(base.onCancel).getOrElse(base)

      finalized.attempt flatMap {
        case Left(e) =>
          doOutcome(Outcome.Errored(e)) *> IO.raiseError(e)
        case Right(a) =>
          doOutcome(Outcome.Succeeded(IO.pure(a))).as(a)
      }
    }
  }

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

  def redeem[B](recover: Throwable => B, map: A => B): IO[B] =
    attempt.map(_.fold(recover, map))

  def redeemWith[B](recover: Throwable => IO[B], bind: A => IO[B]): IO[B] =
    attempt.flatMap(_.fold(recover, bind))

  def delayBy(duration: FiniteDuration): IO[A] =
    IO.sleep(duration) *> this

  def timeout[A2 >: A](duration: FiniteDuration): IO[A2] =
    timeoutTo(duration, IO.raiseError(new TimeoutException(duration.toString)))

  def timeoutTo[A2 >: A](duration: FiniteDuration, fallback: IO[A2]): IO[A2] =
    race(IO.sleep(duration)).flatMap {
      case Right(_) => fallback
      case Left(value) => IO.pure(value)
    }

  def product[B](that: IO[B]): IO[(A, B)] =
    flatMap(a => that.map(b => (a, b)))

  def productL[B](that: IO[B]): IO[A] =
    flatMap(a => that.as(a))

  def productR[B](that: IO[B]): IO[B] =
    flatMap(_ => that)

  def start: IO[FiberIO[A @uncheckedVariance]] =
    IO.Start(this)

  def background: ResourceIO[IO[OutcomeIO[A @uncheckedVariance]]] =
    Spawn[IO].background(this)

  def memoize: IO[IO[A]] =
    Concurrent[IO].memoize(this)

  def uncancelable: IO[A] =
    IO.uncancelable(_ => this)

  def void: IO[Unit] =
    map(_ => ())

  override def toString: String = "IO(...)"

  // unsafe stuff

  def unsafeRunAsync(cb: Either[Throwable, A] => Unit)(
      implicit runtime: unsafe.IORuntime): Unit = {
    unsafeRunFiber(
      cb(Left(new CancellationException("Main fiber was canceled"))),
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

  def unsafeRunAndForget()(implicit runtime: unsafe.IORuntime): Unit =
    unsafeRunAsync(_ => ())

  def unsafeToFuture()(implicit runtime: unsafe.IORuntime): Future[A] = {
    val p = Promise[A]()

    unsafeRunAsync {
      case Left(t) => p.failure(t)
      case Right(a) => p.success(a)
    }

    p.future
  }

  private[effect] def unsafeRunFiber(
      canceled: => Unit,
      failure: Throwable => Unit,
      success: A => Unit)(implicit runtime: unsafe.IORuntime): IOFiber[A @uncheckedVariance] = {

    val fiber = new IOFiber[A](
      0,
      oc =>
        oc.fold(
          canceled,
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

  type Par[A] = ParallelF[IO, A]

  // constructors

  def apply[A](thunk: => A): IO[A] = Delay(() => thunk)

  def delay[A](thunk: => A): IO[A] = apply(thunk)

  def defer[A](thunk: => IO[A]): IO[A] =
    delay(thunk).flatten

  def async[A](k: (Either[Throwable, A] => Unit) => IO[Option[IO[Unit]]]): IO[A] =
    asyncForIO.async(k)

  def async_[A](k: (Either[Throwable, A] => Unit) => Unit): IO[A] =
    asyncForIO.async_(k)

  def canceled: IO[Unit] = Canceled

  def cede: IO[Unit] = Cede

  /**
   * This is a low-level API which is meant for implementors,
   * please use `background`, `start`, `async`, or `Deferred` instead,
   * depending on the use case
   */
  def cont[A](body: Cont[IO, A]): IO[A] =
    IOCont[A](body)

  def executionContext: IO[ExecutionContext] = ReadEC

  def monotonic: IO[FiniteDuration] = Monotonic

  def never[A]: IO[A] = _never

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

  def pure[A](value: A): IO[A] = Pure(value)

  def raiseError[A](t: Throwable): IO[A] = Error(t)

  def realTime: IO[FiniteDuration] = RealTime

  def sleep(delay: FiniteDuration): IO[Unit] =
    Sleep(delay)

  def uncancelable[A](body: Poll[IO] => IO[A]): IO[A] =
    Uncancelable(body)

  private[this] val _unit: IO[Unit] = Pure(())
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

  def fromFuture[A](fut: IO[Future[A]]): IO[A] =
    asyncForIO.fromFuture(fut)

  def race[A, B](left: IO[A], right: IO[B]): IO[Either[A, B]] =
    asyncForIO.race(left, right)

  def racePair[A, B](
      left: IO[A],
      right: IO[B]): IO[Either[(OutcomeIO[A], FiberIO[B]), (FiberIO[A], OutcomeIO[B])]] =
    asyncForIO.racePair(left, right)

  def ref[A](a: A): IO[Ref[IO, A]] = IO(Ref.unsafe(a))

  def deferred[A]: IO[Deferred[IO, A]] = IO(Deferred.unsafe)

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

  def eval[A](fa: Eval[A]): IO[A] =
    fa match {
      case Now(a) => pure(a)
      case notNow => apply(notNow.value)
    }

  def fromOption[A](o: Option[A])(orElse: => Throwable): IO[A] =
    o match {
      case None => raiseError(orElse)
      case Some(value) => pure(value)
    }

  def fromEither[A](e: Either[Throwable, A]): IO[A] =
    e match {
      case Right(a) => pure(a)
      case Left(err) => raiseError(err)
    }

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

  private[this] val _asyncForIO: kernel.Async[IO] = new kernel.Async[IO]
    with StackSafeMonad[IO] {

    override def as[A, B](ioa: IO[A], b: B): IO[B] =
      ioa.as(b)

    override def attempt[A](ioa: IO[A]) =
      ioa.attempt

    def forceR[A, B](left: IO[A])(right: IO[B]): IO[B] =
      left.attempt.productR(right)

    def pure[A](x: A): IO[A] =
      IO.pure(x)

    def handleErrorWith[A](fa: IO[A])(f: Throwable => IO[A]): IO[A] =
      fa.handleErrorWith(f)

    def raiseError[A](e: Throwable): IO[A] =
      IO.raiseError(e)

    def cont[A](body: Cont[IO, A]): IO[A] = IO.cont(body)

    def evalOn[A](fa: IO[A], ec: ExecutionContext): IO[A] =
      fa.evalOn(ec)

    val executionContext: IO[ExecutionContext] =
      IO.executionContext

    def onCancel[A](ioa: IO[A], fin: IO[Unit]): IO[A] =
      ioa.onCancel(fin)

    override def bracketCase[A, B](acquire: IO[A])(use: A => IO[B])(
        release: (A, OutcomeIO[B]) => IO[Unit]): IO[B] =
      acquire.bracketCase(use)(release)

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

    override def interruptible[A](many: Boolean)(thunk: => A) = IO.interruptible(many)(thunk)

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

  final private[effect] case class Pure[+A](value: A) extends IO[A] {
    def tag = 0
    override def toString: String = s"IO($value)"
  }

  private[effect] final case class Map[E, +A](ioe: IO[E], f: E => A) extends IO[A] {
    def tag = 1
  }

  private[effect] final case class FlatMap[E, +A](ioe: IO[E], f: E => IO[A]) extends IO[A] {
    def tag = 2
  }

  private[effect] final case class Error(t: Throwable) extends IO[Nothing] { def tag = 3 }

  private[effect] final case class Attempt[+A](ioa: IO[A]) extends IO[Either[Throwable, A]] {
    def tag = 4
  }

  private[effect] final case class HandleErrorWith[+A](ioa: IO[A], f: Throwable => IO[A])
      extends IO[A] {
    def tag = 5
  }

  // we keep Delay as a separate case as a fast-path, since the added tags don't appear to confuse HotSpot (for reasons unknown)
  private[effect] final case class Delay[+A](thunk: () => A) extends IO[A] { def tag = 6 }

  private[effect] case object Canceled extends IO[Unit] { def tag = 7 }

  private[effect] final case class OnCancel[+A](ioa: IO[A], fin: IO[Unit]) extends IO[A] {
    def tag = 8
  }

  private[effect] final case class Uncancelable[+A](body: Poll[IO] => IO[A]) extends IO[A] {
    def tag = 9
  }
  private[effect] object Uncancelable {
    // INTERNAL, it's only created by the runloop itself during the execution of `Uncancelable`
    final case class UnmaskRunLoop[+A](ioa: IO[A], id: Int) extends IO[A] {
      def tag = 10
    }
  }

  // Low level construction that powers `async`
  private[effect] final case class IOCont[A](body: Cont[IO, A]) extends IO[A] {
    def tag = 11
  }
  private[effect] object IOCont {
    // INTERNAL, it's only created by the runloop itself during the execution of `IOCont`
    final case class Get[A](state: AtomicReference[ContState], wasFinalizing: AtomicBoolean)
        extends IO[A] {
      def tag = 12
    }
  }

  private[effect] case object Cede extends IO[Unit] { def tag = 13 }

  private[effect] final case class Start[A](ioa: IO[A]) extends IO[FiberIO[A]] {
    def tag = 14
  }

  private[effect] final case class Sleep(delay: FiniteDuration) extends IO[Unit] {
    def tag = 15
  }

  private[effect] case object RealTime extends IO[FiniteDuration] { def tag = 16 }

  private[effect] case object Monotonic extends IO[FiniteDuration] { def tag = 17 }

  private[effect] case object ReadEC extends IO[ExecutionContext] { def tag = 18 }

  private[effect] final case class EvalOn[+A](ioa: IO[A], ec: ExecutionContext) extends IO[A] {
    def tag = 19
  }

  private[effect] final case class Blocking[+A](hint: Sync.Type, thunk: () => A) extends IO[A] {
    def tag = 20
  }

  // INTERNAL, only created by the runloop itself as the terminal state of several operations
  private[effect] case object EndFiber extends IO[Nothing] {
    def tag = -1
  }

}
