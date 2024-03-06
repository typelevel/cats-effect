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

import cats.{Align, Eval, Functor, Now, Show, StackSafeMonad}
import cats.data.Ior
import cats.effect.syntax.monadCancel._
import cats.kernel.{Monoid, Semigroup}
import cats.syntax.all._

import scala.annotation.{switch, tailrec}
import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.NonFatal

import Platform.static

/**
 * A pure abstraction representing the intention to perform a side effect, where the result of
 * that side effect is obtained synchronously.
 *
 * `SyncIO` is similar to [[IO]], but does not support asynchronous computations. Consequently,
 * a `SyncIO` can be run synchronously on any platform to obtain a result via `unsafeRunSync`.
 * This is unlike `IO#unsafeRunSync`, which cannot be safely called in general -- doing so on
 * the JVM blocks the calling thread while the async part of the computation is run and doing so
 * on Scala.js is not supported.
 */
sealed abstract class SyncIO[+A] private () extends Serializable {

  private[effect] def tag: Byte

  /**
   * Alias for `productL`.
   *
   * @see
   *   [[SyncIO#productL]]
   */
  def <*[B](that: SyncIO[B]): SyncIO[A] =
    productL(that)

  /**
   * Alias for `productR`.
   *
   * @see
   *   [[SyncIO#productR]]
   */
  def *>[B](that: SyncIO[B]): SyncIO[B] =
    productR(that)

  /**
   * Alias for `flatMap(_ => that)`.
   *
   * @see
   *   [[SyncIO#flatMap]]
   */
  def >>[B](that: => SyncIO[B]): SyncIO[B] =
    flatMap(_ => that)

  // Necessary overload to preserve binary compatibility #1947
  private[effect] def >>[B](that: SyncIO[B]): SyncIO[B] =
    flatMap(_ => that)

  /**
   * Alias for `map(_ => b)`.
   *
   * @see
   *   [[SyncIO#map]]
   */
  def as[B](b: B): SyncIO[B] =
    map(_ => b)

  /**
   * Materializes any sequenced exceptions into value space, where they may be handled.
   *
   * This is analogous to the `catch` clause in `try`/`catch`, being the inverse of
   * `SyncIO.raiseError`. Thus:
   *
   * {{{
   * SyncIO.raiseError(ex).attempt.unsafeRunSync === Left(ex)
   * }}}
   *
   * @see
   *   [[SyncIO.raiseError]]
   */
  def attempt: SyncIO[Either[Throwable, A]] =
    SyncIO.Attempt(this)

  /**
   * Monadic bind on `SyncIO`, used for sequentially composing two `SyncIO` actions, where the
   * value produced by the first `SyncIO` is passed as input to a function producing the second
   * `SyncIO` action.
   *
   * Due to this operation's signature, `flatMap` forces a data dependency between two `SyncIO`
   * actions, thus ensuring sequencing (e.g. one action to be executed before another one).
   *
   * Any exceptions thrown within the function will be caught and sequenced in to the result
   * `SyncIO[B]`.
   *
   * @param f
   *   the bind function
   * @return
   *   `SyncIO` produced by applying `f` to the result of the current `SyncIO`
   */
  def flatMap[B](f: A => SyncIO[B]): SyncIO[B] =
    SyncIO.FlatMap(this, f)

  /**
   * Handle any error, potentially recovering from it, by mapping it to another `SyncIO` value.
   *
   * Implements `ApplicativeError.handleErrorWith`.
   *
   * @param f
   * @return
   */
  def handleErrorWith[B >: A](f: Throwable => SyncIO[B]): SyncIO[B] =
    SyncIO.HandleErrorWith(this, f)

  /**
   * Functor map on `SyncIO`. Given a mapping function, it transforms the value produced by the
   * source, while keeping the `SyncIO` context.
   *
   * Any exceptions thrown within the function will be caught and sequenced into the result
   * `SyncIO[B]`.
   *
   * @param f
   *   the mapping function
   * @return
   *   `SyncIO` that evaluates to the value obtained by applying `f` to the result of the
   *   current `SyncIO`
   */
  def map[B](f: A => B): SyncIO[B] =
    SyncIO.Map(this, f)

  /**
   * Executes `that` only for the side effects.
   *
   * @param that
   *   `SyncIO` to be executed after this `SyncIO`
   * @return
   *   `SyncIO` which sequences the effects of `that` but evaluates to the result of this
   *   `SyncIO`
   */
  def productL[B](that: SyncIO[B]): SyncIO[A] =
    flatMap(a => that.as(a))

  /**
   * Sequences `that` without propagating the value of the current `SyncIO`.
   *
   * @param that
   *   `SyncIO` to be executed after this `SyncIO`
   * @return
   *   `SyncIO` which sequences the effects of `that`
   */
  def productR[B](that: SyncIO[B]): SyncIO[B] =
    flatMap(_ => that)

  def redeem[B](recover: Throwable => B, map: A => B): SyncIO[B] =
    attempt.map(_.fold(recover, map))

  def redeemWith[B](recover: Throwable => SyncIO[B], bind: A => SyncIO[B]): SyncIO[B] =
    attempt.flatMap(_.fold(recover, bind))

  /**
   * Alias for `map(_ => ())`.
   *
   * @see
   *   [[SyncIO#map]]
   */
  def void: SyncIO[Unit] =
    map(_ => ())

  override def toString(): String = "SyncIO(...)"

  /**
   * Translates this [[SyncIO]] to any `F[_]` data type that implements [[Sync]].
   */
  def to[F[_]](implicit F: Sync[F]): F[A @uncheckedVariance] = {
    def interpret[B](sio: SyncIO[B]): F[B] =
      sio match {
        case SyncIO.Pure(a) => F.pure(a)
        case SyncIO.Suspend(hint, thunk) => F.suspend(hint)(thunk())
        case SyncIO.Error(t) => F.raiseError(t)
        case SyncIO.Map(sioe, f) => interpret(sioe).map(f)
        case SyncIO.FlatMap(sioe, f) => interpret(sioe).flatMap(f.andThen(interpret))
        case SyncIO.HandleErrorWith(sioa, f) =>
          interpret(sioa).handleErrorWith(f.andThen(interpret))
        case SyncIO.Success(_) | SyncIO.Failure(_) => sys.error("impossible")
        case SyncIO.Attempt(sioa) => interpret(sioa).attempt.asInstanceOf[F[B]]
        case SyncIO.RealTime => F.realTime.asInstanceOf[F[B]]
        case SyncIO.Monotonic => F.monotonic.asInstanceOf[F[B]]
      }

    interpret(this).uncancelable
  }

  // unsafe

  /**
   * Produces the result by running the encapsulated effects as impure side effects.
   *
   * Any exceptions raised within the effect will be re-thrown during evaluation.
   *
   * As the name says, this is an UNSAFE function as it is impure and performs side effects and
   * throws exceptions. You should ideally only call this function *once*, at the very end of
   * your program.
   *
   * @return
   *   the result of evaluating this `SyncIO`
   */
  def unsafeRunSync(): A = {
    import SyncIOConstants._

    var conts = ByteStack.create(8)
    val objectState = ArrayStack[AnyRef](16)

    conts = ByteStack.push(conts, RunTerminusK)

    @tailrec
    def runLoop(cur0: SyncIO[Any]): A =
      (cur0.tag: @switch) match {
        case 0 =>
          val cur = cur0.asInstanceOf[SyncIO.Pure[Any]]
          runLoop(succeeded(cur.value, 0))

        case 1 =>
          val cur = cur0.asInstanceOf[SyncIO.Suspend[Any]]

          var error: Throwable = null
          val r =
            try cur.thunk()
            catch {
              case t if NonFatal(t) => error = t
            }

          val next =
            if (error == null) succeeded(r, 0)
            else failed(error, 0)

          runLoop(next)

        case 2 =>
          val cur = cur0.asInstanceOf[SyncIO.Error]
          runLoop(failed(cur.t, 0))

        case 3 =>
          val cur = cur0.asInstanceOf[SyncIO.Map[Any, Any]]

          objectState.push(cur.f)
          conts = ByteStack.push(conts, MapK)

          runLoop(cur.ioe)

        case 4 =>
          val cur = cur0.asInstanceOf[SyncIO.FlatMap[Any, Any]]

          objectState.push(cur.f)
          conts = ByteStack.push(conts, FlatMapK)

          runLoop(cur.ioe)

        case 5 =>
          val cur = cur0.asInstanceOf[SyncIO.HandleErrorWith[Any]]

          objectState.push(cur.f)
          conts = ByteStack.push(conts, HandleErrorWithK)

          runLoop(cur.ioa)

        case 6 =>
          val cur = cur0.asInstanceOf[SyncIO.Success[A]]
          cur.value

        case 7 =>
          val cur = cur0.asInstanceOf[SyncIO.Failure]
          throw cur.t

        case 8 =>
          val cur = cur0.asInstanceOf[SyncIO.Attempt[Any]]

          conts = ByteStack.push(conts, AttemptK)
          runLoop(cur.ioa)

        case 9 =>
          runLoop(succeeded(System.currentTimeMillis().millis, 0))

        case 10 =>
          runLoop(succeeded(System.nanoTime().nanos, 0))
      }

    @tailrec
    def succeeded(result: Any, depth: Int): SyncIO[Any] =
      (ByteStack.pop(conts): @switch) match {
        case 0 => mapK(result, depth)
        case 1 => flatMapK(result, depth)
        case 2 =>
          // handleErrorWithK
          // this is probably faster than the pre-scan we do in failed, since handlers are rarer than flatMaps
          objectState.pop()
          succeeded(result, depth)
        case 3 => SyncIO.Success(result)
        case 4 => succeeded(Right(result), depth + 1)
      }

    def failed(error: Throwable, depth: Int): SyncIO[Any] = {
      /*val buffer = conts.unsafeBuffer()

      var i = conts.unsafeIndex() - 1
      val orig = i
      var k: Byte = -1

      while (i >= 0 && k < 0) {
        if (buffer(i) == FlatMapK || buffer(i) == MapK)
          i -= 1
        else
          k = buffer(i)
      }

      conts.unsafeSet(i)
      objectState.unsafeSet(objectState.unsafeIndex() - (orig - i))*/

      (ByteStack.pop(conts): @switch) match {
        case 0 | 1 =>
          objectState.pop()
          failed(error, depth)
        case 2 => handleErrorWithK(error, depth)
        case 3 => SyncIO.Failure(error)
        case 4 => succeeded(Left(error), depth + 1)
      }
    }

    def mapK(result: Any, depth: Int): SyncIO[Any] = {
      val f = objectState.pop().asInstanceOf[Any => Any]

      var error: Throwable = null
      val transformed =
        try f(result)
        catch {
          case t if NonFatal(t) => error = t
        }

      if (depth > MaxStackDepth) {
        if (error == null) SyncIO.Pure(transformed)
        else SyncIO.Error(error)
      } else {
        if (error == null) succeeded(transformed, depth + 1)
        else failed(error, depth + 1)
      }
    }

    def flatMapK(result: Any, depth: Int): SyncIO[Any] = {
      val f = objectState.pop().asInstanceOf[Any => SyncIO[Any]]

      try f(result)
      catch {
        case t if NonFatal(t) => failed(t, depth + 1)
      }
    }

    def handleErrorWithK(t: Throwable, depth: Int): SyncIO[Any] = {
      val f = objectState.pop().asInstanceOf[Throwable => SyncIO[Any]]

      try f(t)
      catch {
        case t if NonFatal(t) => failed(t, depth + 1)
      }
    }

    runLoop(this)
  }
}

private[effect] trait SyncIOLowPriorityImplicits {

  implicit def semigroupForIO[A: Semigroup]: Semigroup[SyncIO[A]] =
    new SyncIOSemigroup[A]

  protected class SyncIOSemigroup[A](implicit val A: Semigroup[A])
      extends Semigroup[SyncIO[A]] {
    def combine(x: SyncIO[A], y: SyncIO[A]): SyncIO[A] =
      x.flatMap(l => y.map(r => A.combine(l, r)))
  }
}

object SyncIO extends SyncIOCompanionPlatform with SyncIOLowPriorityImplicits {

  @static private[this] val Delay = Sync.Type.Delay
  @static private[this] val _syncForSyncIO: Sync[SyncIO] = new SyncIOSync

  // constructors

  /**
   * Suspends a synchronous side effect in `SyncIO`.
   *
   * Any exceptions thrown by the effect will be caught and sequenced into the `SyncIO`.
   *
   * @param thunk
   *   side effectful expression to be suspended in `SyncIO`
   * @return
   *   a `SyncIO` that will be evaluated to the side effectful expression `thunk`
   */
  def apply[A](thunk: => A): SyncIO[A] =
    Suspend(Delay, () => thunk)

  /**
   * Suspends a synchronous side effect which produces a `SyncIO` in `SyncIO`.
   *
   * This is useful for trampolining (i.e. when the side effect is conceptually the allocation
   * of a stack frame). Any exceptions thrown by the side effect will be caught and sequenced
   * into the `SyncIO`.
   *
   * @param thunk
   *   `SyncIO` expression to be suspended in `SyncIO`
   * @return
   *   a `SyncIO` that will be evaluated to the value of the suspended `thunk`
   */
  def defer[A](thunk: => SyncIO[A]): SyncIO[A] =
    apply(thunk).flatMap(identity)

  /**
   * Alias for `apply`.
   *
   * @see
   *   [[SyncIO.apply]]
   */
  def delay[A](thunk: => A): SyncIO[A] =
    apply(thunk)

  /**
   * Lifts an `Eval` into `SyncIO`.
   *
   * This function will preserve the evaluation semantics of any actions that are lifted into
   * the pure `SyncIO`. Eager `Eval` instances will be converted into thunk-less `SyncIO` (i.e.
   * eager `SyncIO`), while lazy eval and memoized will be executed as such.
   *
   * @param fa
   *   `Eval` instance to be lifted in `SyncIO`
   * @return
   *   `SyncIO` that will be evaluated to the value of the lifted `fa`
   */
  def eval[A](fa: Eval[A]): SyncIO[A] =
    fa match {
      case Now(a) => pure(a)
      case notNow => apply(notNow.value)
    }

  val monotonic: SyncIO[FiniteDuration] =
    Monotonic

  /**
   * Suspends a pure value in `SyncIO`.
   *
   * This should ''only'' be used if the value in question has "already" been computed! In other
   * words, something like `SyncIO.pure(readLine)` is most definitely not the right thing to do!
   * However, `SyncIO.pure(42)` is correct and will be more efficient (when evaluated) than
   * `SyncIO(42)`, due to avoiding the allocation of extra thunks.
   *
   * @param value
   *   precomputed value used as the result of the `SyncIO`
   * @return
   *   an already evaluated `SyncIO` holding `value`
   */
  def pure[A](value: A): SyncIO[A] =
    Pure(value)

  /**
   * Constructs a `SyncIO` which sequences the specified exception.
   *
   * If this `SyncIO` is run using `unsafeRunSync` the exception will be thrown. This exception
   * can be "caught" (or rather, materialized into value-space) using the `attempt` method.
   *
   * @see
   *   [[SyncIO#attempt]]
   *
   * @param t
   *   `Throwable` value to fail with
   * @return
   *   a `SyncIO` that results in failure with value `t`
   */
  def raiseError[A](t: Throwable): SyncIO[A] =
    Error(t)

  val realTime: SyncIO[FiniteDuration] =
    RealTime

  private[this] val _unit: SyncIO[Unit] =
    Pure(())

  /**
   * Alias for `SyncIO.pure(())`
   *
   * @see
   *   [[SyncIO.pure]]
   */
  def unit: SyncIO[Unit] = _unit

  // utilities

  /**
   * Lifts an `Either[Throwable, A]` into `SyncIO`, raising the throwable if it exists.
   *
   * @param e
   *   either value to be lifted
   * @return
   *   `SyncIO` that evaluates to the value of `e` or fail with its `Throwable` instance
   */
  def fromEither[A](e: Either[Throwable, A]): SyncIO[A] =
    e.fold(raiseError, pure)

  /**
   * Lifts an `Option[A]` into `SyncIO`, raising `orElse` if the provided option value is empty.
   *
   * @param o
   *   option value to be lifted
   * @param orElse
   *   expression that evaluates to `Throwable`
   * @return
   *   `SyncIO` that evaluates to the optional value `o` or fail with the `orElse` expression
   */
  def fromOption[A](o: Option[A])(orElse: => Throwable): SyncIO[A] =
    o.fold(raiseError[A](orElse))(pure)

  /**
   * Lifts a `Try[A]` into `SyncIO`, raising the throwable if it exists.
   *
   * @param t
   *   try value to be lifted
   * @return
   *   `SyncIO` that evaluates to the value of `t` if successful, or fails with its `Throwable`
   *   instance
   */
  def fromTry[A](t: Try[A]): SyncIO[A] =
    t.fold(raiseError, pure)

  // instances

  implicit def showForSyncIO[A](implicit A: Show[A]): Show[SyncIO[A]] =
    Show.show {
      case SyncIO.Pure(a) => s"SyncIO(${A.show(a)})"
      case _ => "SyncIO(...)"
    }

  implicit def monoidForIO[A: Monoid]: Monoid[SyncIO[A]] =
    new SyncIOMonoid[A]

  private class SyncIOMonoid[A](override implicit val A: Monoid[A])
      extends SyncIOSemigroup[A]
      with Monoid[SyncIO[A]] {
    def empty: SyncIO[A] = pure(A.empty)
  }

  implicit def alignForSyncIO: Align[SyncIO] = _alignForSyncIO

  private[this] val _alignForSyncIO = new Align[SyncIO] {
    def align[A, B](fa: SyncIO[A], fb: SyncIO[B]): SyncIO[Ior[A, B]] =
      alignWith(fa, fb)(identity)

    override def alignWith[A, B, C](fa: SyncIO[A], fb: SyncIO[B])(
        f: Ior[A, B] => C): SyncIO[C] =
      fa.redeemWith(
        t => fb.redeemWith(_ => SyncIO.raiseError(t), b => SyncIO.pure(f(Ior.right(b)))),
        a => fb.redeem(_ => f(Ior.left(a)), b => f(Ior.both(a, b)))
      )

    def functor: Functor[SyncIO] = Functor[SyncIO]
  }

  private[this] final class SyncIOSync
      extends Sync[SyncIO]
      with StackSafeMonad[SyncIO]
      with MonadCancel.Uncancelable[SyncIO, Throwable] {

    def pure[A](x: A): SyncIO[A] =
      SyncIO.pure(x)

    def raiseError[A](e: Throwable): SyncIO[A] =
      SyncIO.raiseError(e)

    def handleErrorWith[A](fa: SyncIO[A])(f: Throwable => SyncIO[A]): SyncIO[A] =
      fa.handleErrorWith(f)

    def flatMap[A, B](fa: SyncIO[A])(f: A => SyncIO[B]): SyncIO[B] =
      fa.flatMap(f)

    def monotonic: SyncIO[FiniteDuration] =
      SyncIO.monotonic

    def realTime: SyncIO[FiniteDuration] =
      SyncIO.realTime

    def suspend[A](hint: Sync.Type)(thunk: => A): SyncIO[A] =
      Suspend(hint, () => thunk)

    override def attempt[A](fa: SyncIO[A]): SyncIO[Either[Throwable, A]] =
      fa.attempt

    override def redeem[A, B](fa: SyncIO[A])(recover: Throwable => B, f: A => B): SyncIO[B] =
      fa.redeem(recover, f)

    override def redeemWith[A, B](
        fa: SyncIO[A])(recover: Throwable => SyncIO[B], bind: A => SyncIO[B]): SyncIO[B] =
      fa.redeemWith(recover, bind)

    override def unit: SyncIO[Unit] =
      SyncIO.unit

    def forceR[A, B](fa: SyncIO[A])(fb: SyncIO[B]): SyncIO[B] =
      fa.attempt.productR(fb)
  }

  implicit def syncForSyncIO: Sync[SyncIO] with MonadCancel[SyncIO, Throwable] = _syncForSyncIO

  // implementations

  private final case class Pure[+A](value: A) extends SyncIO[A] {
    def tag = 0
    override def toString: String = s"SyncIO($value)"
  }

  private final case class Suspend[+A](hint: Sync.Type, thunk: () => A) extends SyncIO[A] {
    def tag = 1
  }

  private final case class Error(t: Throwable) extends SyncIO[Nothing] {
    def tag = 2
  }

  private final case class Map[E, +A](ioe: SyncIO[E], f: E => A) extends SyncIO[A] {
    def tag = 3
  }

  private final case class FlatMap[E, +A](ioe: SyncIO[E], f: E => SyncIO[A]) extends SyncIO[A] {
    def tag = 4
  }

  private final case class HandleErrorWith[+A](ioa: SyncIO[A], f: Throwable => SyncIO[A])
      extends SyncIO[A] {
    def tag = 5
  }

  private final case class Success[+A](value: A) extends SyncIO[A] {
    def tag = 6
  }

  private final case class Failure(t: Throwable) extends SyncIO[Nothing] {
    def tag = 7
  }

  private final case class Attempt[+A](ioa: SyncIO[A]) extends SyncIO[Either[Throwable, A]] {
    def tag = 8
  }

  private case object RealTime extends SyncIO[FiniteDuration] {
    def tag = 9
  }

  private case object Monotonic extends SyncIO[FiniteDuration] {
    def tag = 10
  }
}
