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

package cats.effect.kernel

import cats.{~>, Monoid, Semigroup}
import cats.arrow.FunctionK
import cats.data.{EitherT, Ior, IorT, Kleisli, OptionT, WriterT}
import cats.implicits._

import scala.annotation.{nowarn, tailrec}
import scala.concurrent.{ExecutionContext, Future}

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * A typeclass that encodes the notion of suspending asynchronous side effects in the `F[_]`
 * context
 *
 * An asynchronous task is one whose results are computed somewhere else (eg by a
 * [[scala.concurrent.Future]] running on some other threadpool). We await the results of that
 * execution by giving it a callback to be invoked with the result.
 *
 * That computation may fail hence the callback is of type `Either[Throwable, A] => ()`. This
 * awaiting is semantic only - no threads are blocked, the current fiber is simply descheduled
 * until the callback completes.
 *
 * This leads us directly to the simplest asynchronous FFI
 * {{{
 * def async_[A](k: (Either[Throwable, A] => Unit) => Unit): F[A]
 * }}}
 *
 * {{{async(k)}}} is semantically blocked until the callback is invoked.
 *
 * `async_` is somewhat constrained however. We can't perform any `F[_]` effects in the process
 * of registering the callback and we also can't register a finalizer to eg cancel the
 * asynchronous task in the event that the fiber running `async_` is canceled.
 *
 * This leads us directly to the more general asynchronous FFI
 * {{{
 * def async[A](k: (Either[Throwable, A] => Unit) => F[Option[F[Unit]]]): F[A]
 * }}}
 *
 * As evidenced by the type signature, `k` may perform `F[_]` effects and it returns an
 * `Option[F[Unit]]` which is an optional finalizer to be run in the event that the fiber
 * running {{{async(k)}}} is canceled.
 */
trait Async[F[_]] extends AsyncPlatform[F] with Sync[F] with Temporal[F] {

  /**
   * Suspends an asynchronous side effect with optional immediate result in `F`.
   *
   * The given function `k` will be invoked during evaluation of `F` to:
   *   - check if result is already available;
   *   - "schedule" the asynchronous callback, where the callback of type `Either[Throwable, A]
   *     \=> Unit` is the parameter passed to that function. Only the ''first'' invocation of
   *     the callback will be effective! All subsequent invocations will be silently dropped.
   *
   * The process of registering the callback itself is suspended in `F` (the outer `F` of
   * `F[Either[Option[F[Unit]], A]]`).
   *
   * The effect returns `Either[Option[F[Unit]], A]` where:
   *   - right side `A` is an immediate result of computation (callback invocation will be
   *     dropped);
   *   - left side `Option[F[Unit]]` is an optional finalizer to be run in the event that the
   *     fiber running `asyncCheckAttempt(k)` is canceled.
   *
   * Also, note that `asyncCheckAttempt` is uncancelable during its registration.
   *
   * @see
   *   [[async]] for a simplified variant without an option for immediate result
   * @see
   *   [[async_]] for a simplified variant without an option for immediate result or finalizer
   */
  def asyncCheckAttempt[A](
      k: (Either[Throwable, A] => Unit) => F[Either[Option[F[Unit]], A]]): F[A] = {
    val body = new Cont[F, A, A] {
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

    cont(body)
  }

  /**
   * Suspends an asynchronous side effect in `F`.
   *
   * The given function `k` will be invoked during evaluation of the `F` to "schedule" the
   * asynchronous callback, where the callback of type `Either[Throwable, A] => Unit` is the
   * parameter passed to that function. Only the ''first'' invocation of the callback will be
   * effective! All subsequent invocations will be silently dropped.
   *
   * The process of registering the callback itself is suspended in `F` (the outer `F` of
   * `F[Option[F[Unit]]]`).
   *
   * The effect returns `Option[F[Unit]]` which is an optional finalizer to be run in the event
   * that the fiber running `async(k)` is canceled.
   *
   * @note
   *   `async` is always uncancelable during its registration. The created effect will be
   *   uncancelable during its execution if the registration callback provides no finalizer
   *   (i.e. evaluates to `None`). If you need the created task to be cancelable, return a
   *   finalizer effect upon the registration. In a rare case when there's nothing to finalize,
   *   you can return `Some(F.unit)` for that.
   *
   * @see
   *   [[async_]] for a simplified variant without a finalizer
   * @see
   *   [[asyncCheckAttempt]] for more generic version with option of providing immediate result
   *   of computation
   */
  def async[A](k: (Either[Throwable, A] => Unit) => F[Option[F[Unit]]]): F[A] =
    asyncCheckAttempt[A](cb => map(k(cb))(Left(_)))

  /**
   * Suspends an asynchronous side effect in `F`.
   *
   * The given function `k` will be invoked during evaluation of the `F` to "schedule" the
   * asynchronous callback, where the callback is the parameter passed to that function. Only
   * the ''first'' invocation of the callback will be effective! All subsequent invocations will
   * be silently dropped.
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
   *   [[asyncCheckAttempt]] for more generic version with option of providing immediate result
   *   of computation and finalizer
   */
  def async_[A](k: (Either[Throwable, A] => Unit) => Unit): F[A] =
    async[A](cb => as(delay(k(cb)), None))

  /**
   * An effect that never terminates.
   *
   * Polymorphic so it can be used in situations where an arbitrary effect is expected eg
   * [[Fiber.joinWithNever]]
   */
  def never[A]: F[A] = async(_ => pure(Some(unit)))

  /**
   * Shift execution of the effect `fa` to the execution context `ec`. Execution is shifted back
   * to the previous execution context when `fa` completes.
   *
   * evalOn(executionContext, ec) <-> pure(ec)
   */
  def evalOn[A](fa: F[A], ec: ExecutionContext): F[A]

  /**
   * [[Async.evalOn]] with provided [[java.util.concurrent.Executor]]
   */
  def evalOnExecutor[A](fa: F[A], executor: Executor): F[A] = {
    require(executor != null, "Cannot pass null Executor as an argument")
    executor match {
      case ec: ExecutionContext =>
        evalOn[A](fa, ec: ExecutionContext)
      case executor =>
        flatMap(executionContext) { refEc =>
          val newEc: ExecutionContext =
            ExecutionContext.fromExecutor(executor, refEc.reportFailure)
          evalOn[A](fa, newEc)
        }
    }
  }

  /**
   * [[Async.evalOn]] as a natural transformation.
   */
  def evalOnK(ec: ExecutionContext): F ~> F =
    new (F ~> F) {
      def apply[A](fa: F[A]): F[A] = evalOn(fa, ec)
    }

  /**
   * [[Async.evalOnExecutor]] as a natural transformation.
   */
  def evalOnExecutorK(executor: Executor): F ~> F =
    new (F ~> F) {
      def apply[A](fa: F[A]): F[A] = evalOnExecutor(fa, executor)
    }

  /**
   * Start a new fiber on a different execution context.
   *
   * See [[GenSpawn.start]] for more details.
   */
  def startOn[A](fa: F[A], ec: ExecutionContext): F[Fiber[F, Throwable, A]] =
    evalOn(start(fa), ec)

  /**
   * Start a new fiber on a different executor.
   *
   * See [[GenSpawn.start]] for more details.
   */
  def startOnExecutor[A](fa: F[A], executor: Executor): F[Fiber[F, Throwable, A]] =
    evalOnExecutor(start(fa), executor)

  /**
   * Start a new background fiber on a different execution context.
   *
   * See [[GenSpawn.background]] for more details.
   */
  def backgroundOn[A](
      fa: F[A],
      ec: ExecutionContext): Resource[F, F[Outcome[F, Throwable, A]]] =
    Resource.make(startOn(fa, ec))(_.cancel)(this).map(_.join)

  /**
   * Start a new background fiber on a different executor.
   *
   * See [[GenSpawn.background]] for more details.
   */
  def backgroundOnExecutor[A](
      fa: F[A],
      executor: Executor): Resource[F, F[Outcome[F, Throwable, A]]] =
    Resource.make(startOnExecutor(fa, executor))(_.cancel)(this).map(_.join)

  /**
   * Obtain a reference to the current execution context.
   */
  def executionContext: F[ExecutionContext]

  /**
   * Obtain a reference to the current execution context as a `java.util.concurrent.Executor`.
   */
  def executor: F[Executor] = map(executionContext) {
    case exec: Executor => exec
    case ec => ec.execute(_)
  }

  /**
   * Lifts a [[scala.concurrent.Future]] into an `F` effect.
   *
   * @see
   *   [[fromFutureCancelable]] for a cancelable version
   */
  def fromFuture[A](fut: F[Future[A]]): F[A] =
    flatMap(executionContext) { implicit ec =>
      uncancelable { poll =>
        flatMap(poll(fut)) { f => async_[A](cb => f.onComplete(t => cb(t.toEither))) }
      }
    }

  /**
   * Like [[fromFuture]], but is cancelable via the provided finalizer.
   */
  def fromFutureCancelable[A](futCancel: F[(Future[A], F[Unit])]): F[A] =
    flatMap(executionContext) { implicit ec =>
      uncancelable { poll =>
        flatMap(poll(futCancel)) {
          case (fut, fin) =>
            onCancel(
              poll(async[A](cb => as(delay(fut.onComplete(t => cb(t.toEither))), Some(unit)))),
              fin)
        }
      }
    }

  /**
   * Translates this `F[A]` into a `G` value which, when evaluated, runs the original `F` to its
   * completion, the `limit` number of stages, or until the first stage that cannot be expressed
   * with [[Sync]] (typically an asynchronous boundary).
   *
   * Note that `syncStep` is merely a hint to the runtime system; implementations have the
   * liberty to interpret this method to their liking as long as it obeys the respective laws.
   * For example, a lawful implementation of this function is `G.pure(Left(fa))`, in which case
   * the original `F[A]` value is returned unchanged.
   *
   * @param limit
   *   The maximum number of stages to evaluate prior to forcibly yielding to `F`
   */
  @nowarn("msg=never used")
  def syncStep[G[_], A](fa: F[A], limit: Int)(implicit G: Sync[G]): G[Either[F[A], A]] =
    G.pure(Left(fa))

  /*
   * NOTE: This is a very low level api, end users should use `async` instead.
   * See cats.effect.kernel.Cont for more detail.
   *
   * If you are an implementor, and you have `async` or `asyncCheckAttempt`,
   * `Async.defaultCont` provides an implementation of `cont` in terms of `async`.
   * Note that if you use `defaultCont` you _have_ to override `async/asyncCheckAttempt`.
   */
  def cont[K, R](body: Cont[F, K, R]): F[R]
}

object Async {
  def apply[F[_]](implicit F: Async[F]): F.type = F

  def defaultCont[F[_], K, R](body: Cont[F, K, R])(implicit F: Async[F]): F[R] = {
    sealed trait State
    case class Initial() extends State
    case class Value(v: Either[Throwable, K]) extends State
    case class Waiting(cb: Either[Throwable, K] => Unit) extends State

    F.delay(new AtomicReference[State](Initial())).flatMap { state =>
      def get: F[K] =
        F.defer {
          state.get match {
            case Value(v) => F.fromEither(v)
            case Initial() =>
              F.async { cb =>
                val waiting = Waiting(cb)

                @tailrec
                def loop(): Unit =
                  state.get match {
                    case s @ Initial() =>
                      state.compareAndSet(s, waiting)
                      loop()
                    case Waiting(_) => ()
                    case Value(v) => cb(v)
                  }

                def onCancel = F.delay(state.compareAndSet(waiting, Initial())).void

                F.delay(loop()).as(onCancel.some)
              }
            case Waiting(_) =>
              /*
               * - `cont` forbids concurrency, so no other `get` can be in Waiting.
               * -  if a previous get has succeeded or failed and we are being sequenced
               *    afterwards, it means `resume` has set the state to `Value`.
               * - if a previous `get` has been interrupted and we are running as part of
               *   its finalisers, the state would have been either restored to `Initial`
               *   by the finaliser of that `get`, or set to `Value` by `resume`
               */
              sys.error("Impossible")
          }
        }

      def resume(v: Either[Throwable, K]): Unit = {
        @tailrec
        def loop(): Unit =
          state.get match {
            case Value(_) => () /* idempotent, double calls are forbidden */
            case s @ Initial() =>
              if (!state.compareAndSet(s, Value(v))) loop()
              else ()
            case s @ Waiting(cb) =>
              if (state.compareAndSet(s, Value(v))) cb(v)
              else loop()
          }

        loop()
      }

      body[F].apply(resume, get, FunctionK.id)
    }
  }

  implicit def asyncForOptionT[F[_]](implicit F0: Async[F]): Async[OptionT[F, *]] =
    new OptionTAsync[F] {
      override implicit protected def F: Async[F] = F0
    }

  implicit def asyncForEitherT[F[_], E](implicit F0: Async[F]): Async[EitherT[F, E, *]] =
    new EitherTAsync[F, E] {
      override implicit protected def F: Async[F] = F0
    }

  implicit def asyncForIorT[F[_], L](
      implicit F0: Async[F],
      L0: Semigroup[L]): Async[IorT[F, L, *]] =
    new IorTAsync[F, L] {
      override implicit protected def F: Async[F] = F0

      override implicit protected def L: Semigroup[L] = L0
    }

  implicit def asyncForWriterT[F[_], L](
      implicit F0: Async[F],
      L0: Monoid[L]): Async[WriterT[F, L, *]] =
    new WriterTAsync[F, L] {
      override implicit protected def F: Async[F] = F0

      override implicit protected def L: Monoid[L] = L0
    }

  implicit def asyncForKleisli[F[_], R](implicit F0: Async[F]): Async[Kleisli[F, R, *]] =
    new KleisliAsync[F, R] {
      override implicit protected def F: Async[F] = F0
    }

  private[effect] trait OptionTAsync[F[_]]
      extends Async[OptionT[F, *]]
      with Sync.OptionTSync[F]
      with Temporal.OptionTTemporal[F, Throwable] {

    implicit protected def F: Async[F]

    override protected final def delegate = super.delegate
    override protected final def C: Clock[F] = F

    override def unique: OptionT[F, Unique.Token] =
      delay(new Unique.Token())

    override def syncStep[G[_], A](fa: OptionT[F, A], limit: Int)(
        implicit G: Sync[G]): G[Either[OptionT[F, A], A]] =
      G.map(F.syncStep[G, Option[A]](fa.value, limit)) {
        case Left(foption) => Left(OptionT(foption))
        case Right(None) => Left(OptionT.none)
        case Right(Some(a)) => Right(a)
      }

    def cont[K, R](body: Cont[OptionT[F, *], K, R]): OptionT[F, R] =
      OptionT(
        F.cont(
          new Cont[F, K, Option[R]] {

            override def apply[G[_]](implicit G: MonadCancel[G, Throwable])
                : (Either[Throwable, K] => Unit, G[K], F ~> G) => G[Option[R]] =
              (cb, ga, nat) => {
                val natT: OptionT[F, *] ~> OptionT[G, *] =
                  new ~>[OptionT[F, *], OptionT[G, *]] {

                    override def apply[A](fa: OptionT[F, A]): OptionT[G, A] =
                      OptionT(nat(fa.value))

                  }

                body[OptionT[G, *]].apply(cb, OptionT.liftF(ga), natT).value
              }
          }
        )
      )

    def evalOn[A](fa: OptionT[F, A], ec: ExecutionContext): OptionT[F, A] =
      OptionT(F.evalOn(fa.value, ec))

    def executionContext: OptionT[F, ExecutionContext] = OptionT.liftF(F.executionContext)

    override def never[A]: OptionT[F, A] = OptionT.liftF(F.never)

    override def ap[A, B](
        ff: OptionT[F, A => B]
    )(fa: OptionT[F, A]): OptionT[F, B] = delegate.ap(ff)(fa)

    override def pure[A](x: A): OptionT[F, A] = delegate.pure(x)

    override def flatMap[A, B](fa: OptionT[F, A])(f: A => OptionT[F, B]): OptionT[F, B] =
      delegate.flatMap(fa)(f)

    override def tailRecM[A, B](a: A)(f: A => OptionT[F, Either[A, B]]): OptionT[F, B] =
      delegate.tailRecM(a)(f)

    override def raiseError[A](e: Throwable): OptionT[F, A] =
      delegate.raiseError(e)

    override def handleErrorWith[A](fa: OptionT[F, A])(
        f: Throwable => OptionT[F, A]): OptionT[F, A] =
      delegate.handleErrorWith(fa)(f)

  }

  private[effect] trait EitherTAsync[F[_], E]
      extends Async[EitherT[F, E, *]]
      with Sync.EitherTSync[F, E]
      with Temporal.EitherTTemporal[F, E, Throwable] {

    implicit protected def F: Async[F]

    override protected final def delegate = super.delegate
    override protected final def C: Clock[F] = F

    override def unique: EitherT[F, E, Unique.Token] =
      delay(new Unique.Token())

    override def syncStep[G[_], A](fa: EitherT[F, E, A], limit: Int)(
        implicit G: Sync[G]): G[Either[EitherT[F, E, A], A]] =
      G.map(F.syncStep[G, Either[E, A]](fa.value, limit)) {
        case Left(feither) => Left(EitherT(feither))
        case Right(Left(e)) => Left(EitherT.leftT(e))
        case Right(Right(a)) => Right(a)
      }

    def cont[K, R](body: Cont[EitherT[F, E, *], K, R]): EitherT[F, E, R] =
      EitherT(
        F.cont(
          new Cont[F, K, Either[E, R]] {

            override def apply[G[_]](implicit G: MonadCancel[G, Throwable])
                : (Either[Throwable, K] => Unit, G[K], F ~> G) => G[Either[E, R]] =
              (cb, ga, nat) => {
                val natT: EitherT[F, E, *] ~> EitherT[G, E, *] =
                  new ~>[EitherT[F, E, *], EitherT[G, E, *]] {

                    override def apply[A](fa: EitherT[F, E, A]): EitherT[G, E, A] =
                      EitherT(nat(fa.value))

                  }

                body[EitherT[G, E, *]].apply(cb, EitherT.liftF(ga), natT).value
              }
          }
        )
      )

    def evalOn[A](fa: EitherT[F, E, A], ec: ExecutionContext): EitherT[F, E, A] =
      EitherT(F.evalOn(fa.value, ec))

    def executionContext: EitherT[F, E, ExecutionContext] = EitherT.liftF(F.executionContext)

    override def never[A]: EitherT[F, E, A] = EitherT.liftF(F.never)

    override def ap[A, B](
        ff: EitherT[F, E, A => B]
    )(fa: EitherT[F, E, A]): EitherT[F, E, B] = delegate.ap(ff)(fa)

    override def pure[A](x: A): EitherT[F, E, A] = delegate.pure(x)

    override def flatMap[A, B](fa: EitherT[F, E, A])(
        f: A => EitherT[F, E, B]): EitherT[F, E, B] =
      delegate.flatMap(fa)(f)

    override def tailRecM[A, B](a: A)(f: A => EitherT[F, E, Either[A, B]]): EitherT[F, E, B] =
      delegate.tailRecM(a)(f)

    override def raiseError[A](e: Throwable): EitherT[F, E, A] =
      delegate.raiseError(e)

    override def handleErrorWith[A](fa: EitherT[F, E, A])(
        f: Throwable => EitherT[F, E, A]): EitherT[F, E, A] =
      delegate.handleErrorWith(fa)(f)

  }

  private[effect] trait IorTAsync[F[_], L]
      extends Async[IorT[F, L, *]]
      with Sync.IorTSync[F, L]
      with Temporal.IorTTemporal[F, L, Throwable] {

    implicit protected def F: Async[F]

    override protected final def delegate = super.delegate
    override protected final def C: Clock[F] = F

    override def unique: IorT[F, L, Unique.Token] =
      delay(new Unique.Token())

    override def syncStep[G[_], A](fa: IorT[F, L, A], limit: Int)(
        implicit G: Sync[G]): G[Either[IorT[F, L, A], A]] =
      G.map(F.syncStep[G, Ior[L, A]](fa.value, limit)) {
        case Left(fior) => Left(IorT(fior))
        case Right(Ior.Right(a)) => Right(a)
        case Right(ior) => Left(IorT.fromIor(ior))
      }

    def cont[K, R](body: Cont[IorT[F, L, *], K, R]): IorT[F, L, R] =
      IorT(
        F.cont(
          new Cont[F, K, Ior[L, R]] {

            override def apply[G[_]](implicit G: MonadCancel[G, Throwable])
                : (Either[Throwable, K] => Unit, G[K], F ~> G) => G[Ior[L, R]] =
              (cb, ga, nat) => {
                val natT: IorT[F, L, *] ~> IorT[G, L, *] =
                  new ~>[IorT[F, L, *], IorT[G, L, *]] {

                    override def apply[A](fa: IorT[F, L, A]): IorT[G, L, A] =
                      IorT(nat(fa.value))

                  }

                body[IorT[G, L, *]].apply(cb, IorT.liftF(ga), natT).value
              }
          }
        )
      )

    def evalOn[A](fa: IorT[F, L, A], ec: ExecutionContext): IorT[F, L, A] =
      IorT(F.evalOn(fa.value, ec))

    def executionContext: IorT[F, L, ExecutionContext] = IorT.liftF(F.executionContext)

    override def never[A]: IorT[F, L, A] = IorT.liftF(F.never)

    override def ap[A, B](
        ff: IorT[F, L, A => B]
    )(fa: IorT[F, L, A]): IorT[F, L, B] = delegate.ap(ff)(fa)

    override def pure[A](x: A): IorT[F, L, A] = delegate.pure(x)

    override def flatMap[A, B](fa: IorT[F, L, A])(f: A => IorT[F, L, B]): IorT[F, L, B] =
      delegate.flatMap(fa)(f)

    override def tailRecM[A, B](a: A)(f: A => IorT[F, L, Either[A, B]]): IorT[F, L, B] =
      delegate.tailRecM(a)(f)

    override def raiseError[A](e: Throwable): IorT[F, L, A] =
      delegate.raiseError(e)

    override def handleErrorWith[A](fa: IorT[F, L, A])(
        f: Throwable => IorT[F, L, A]): IorT[F, L, A] =
      delegate.handleErrorWith(fa)(f)

  }

  private[effect] trait WriterTAsync[F[_], L]
      extends Async[WriterT[F, L, *]]
      with Sync.WriterTSync[F, L]
      with Temporal.WriterTTemporal[F, L, Throwable] {

    implicit protected def F: Async[F]

    override protected final def delegate = super.delegate
    override protected final def C: Clock[F] = F

    override def unique: WriterT[F, L, Unique.Token] =
      delay(new Unique.Token())

    override def syncStep[G[_], A](fa: WriterT[F, L, A], limit: Int)(
        implicit G: Sync[G]): G[Either[WriterT[F, L, A], A]] =
      G.pure(Left(fa))

    def cont[K, R](body: Cont[WriterT[F, L, *], K, R]): WriterT[F, L, R] =
      WriterT(
        F.cont(
          new Cont[F, K, (L, R)] {

            override def apply[G[_]](implicit G: MonadCancel[G, Throwable])
                : (Either[Throwable, K] => Unit, G[K], F ~> G) => G[(L, R)] =
              (cb, ga, nat) => {
                val natT: WriterT[F, L, *] ~> WriterT[G, L, *] =
                  new ~>[WriterT[F, L, *], WriterT[G, L, *]] {

                    override def apply[A](fa: WriterT[F, L, A]): WriterT[G, L, A] =
                      WriterT(nat(fa.run))

                  }

                body[WriterT[G, L, *]].apply(cb, WriterT.liftF(ga), natT).run
              }
          }
        )
      )

    def evalOn[A](fa: WriterT[F, L, A], ec: ExecutionContext): WriterT[F, L, A] =
      WriterT(F.evalOn(fa.run, ec))

    def executionContext: WriterT[F, L, ExecutionContext] = WriterT.liftF(F.executionContext)

    override def never[A]: WriterT[F, L, A] = WriterT.liftF(F.never)

    override def ap[A, B](
        ff: WriterT[F, L, A => B]
    )(fa: WriterT[F, L, A]): WriterT[F, L, B] = delegate.ap(ff)(fa)

    override def pure[A](x: A): WriterT[F, L, A] = delegate.pure(x)

    override def flatMap[A, B](fa: WriterT[F, L, A])(
        f: A => WriterT[F, L, B]): WriterT[F, L, B] =
      delegate.flatMap(fa)(f)

    override def tailRecM[A, B](a: A)(f: A => WriterT[F, L, Either[A, B]]): WriterT[F, L, B] =
      delegate.tailRecM(a)(f)

    override def raiseError[A](e: Throwable): WriterT[F, L, A] =
      delegate.raiseError(e)

    override def handleErrorWith[A](fa: WriterT[F, L, A])(
        f: Throwable => WriterT[F, L, A]): WriterT[F, L, A] =
      delegate.handleErrorWith(fa)(f)

  }

  private[effect] trait KleisliAsync[F[_], R]
      extends Async[Kleisli[F, R, *]]
      with Sync.KleisliSync[F, R]
      with Temporal.KleisliTemporal[F, R, Throwable] {

    implicit protected def F: Async[F]

    override protected final def delegate = super.delegate
    override protected final def C: Clock[F] = F

    override def unique: Kleisli[F, R, Unique.Token] =
      delay(new Unique.Token())

    override def syncStep[G[_], A](fa: Kleisli[F, R, A], limit: Int)(
        implicit G: Sync[G]): G[Either[Kleisli[F, R, A], A]] =
      G.pure(Left(fa))

    def cont[K, R2](body: Cont[Kleisli[F, R, *], K, R2]): Kleisli[F, R, R2] =
      Kleisli(r =>
        F.cont(
          new Cont[F, K, R2] {

            override def apply[G[_]](implicit G: MonadCancel[G, Throwable])
                : (Either[Throwable, K] => Unit, G[K], F ~> G) => G[R2] =
              (cb, ga, nat) => {
                val natT: Kleisli[F, R, *] ~> Kleisli[G, R, *] =
                  new ~>[Kleisli[F, R, *], Kleisli[G, R, *]] {

                    override def apply[A](fa: Kleisli[F, R, A]): Kleisli[G, R, A] =
                      Kleisli(r => nat(fa.run(r)))

                  }

                body[Kleisli[G, R, *]].apply(cb, Kleisli.liftF(ga), natT).run(r)
              }
          }
        ))

    def evalOn[A](fa: Kleisli[F, R, A], ec: ExecutionContext): Kleisli[F, R, A] =
      Kleisli(r => F.evalOn(fa.run(r), ec))

    def executionContext: Kleisli[F, R, ExecutionContext] = Kleisli.liftF(F.executionContext)

    override def never[A]: Kleisli[F, R, A] = Kleisli.liftF(F.never)

    override def ap[A, B](
        ff: Kleisli[F, R, A => B]
    )(fa: Kleisli[F, R, A]): Kleisli[F, R, B] = delegate.ap(ff)(fa)

    override def pure[A](x: A): Kleisli[F, R, A] = delegate.pure(x)

    override def flatMap[A, B](fa: Kleisli[F, R, A])(
        f: A => Kleisli[F, R, B]): Kleisli[F, R, B] =
      delegate.flatMap(fa)(f)

    override def tailRecM[A, B](a: A)(f: A => Kleisli[F, R, Either[A, B]]): Kleisli[F, R, B] =
      delegate.tailRecM(a)(f)

    override def raiseError[A](e: Throwable): Kleisli[F, R, A] =
      delegate.raiseError(e)

    override def handleErrorWith[A](fa: Kleisli[F, R, A])(
        f: Throwable => Kleisli[F, R, A]): Kleisli[F, R, A] =
      delegate.handleErrorWith(fa)(f)

  }
}
