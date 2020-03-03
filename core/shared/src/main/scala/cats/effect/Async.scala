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

import simulacrum._
import cats.implicits._
import cats.data._
import cats.effect.IO.{Delay, Pure, RaiseError}
import cats.effect.concurrent.{Deferred, Ref, Semaphore}
import cats.effect.internals.{Callback, IORunLoop}
import cats.effect.internals.TrampolineEC.immediate

import scala.annotation.implicitNotFound
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Either, Failure, Success}

/**
 * A monad that can describe asynchronous or synchronous computations
 * that produce exactly one result.
 *
 * ==On Asynchrony==
 *
 * An asynchronous task represents logic that executes independent of
 * the main program flow, or current callstack. It can be a task whose
 * result gets computed on another thread, or on some other machine on
 * the network.
 *
 * In terms of types, normally asynchronous processes are represented as:
 * {{{
 *   (A => Unit) => Unit
 * }}}
 *
 * This signature can be recognized in the "Observer pattern" described
 * in the "Gang of Four", although it should be noted that without
 * an `onComplete` event (like in the Rx Observable pattern) you can't
 * detect completion in case this callback can be called zero or
 * multiple times.
 *
 * Some abstractions allow for signaling an error condition
 * (e.g. `MonadError` data types), so this would be a signature
 * that's closer to Scala's `Future#onComplete`:
 *
 * {{{
 *   (Either[Throwable, A] => Unit) => Unit
 * }}}
 *
 * And many times the abstractions built to deal with asynchronous tasks
 * also provide a way to cancel such processes, to be used in race
 * conditions in order to cleanup resources early:
 *
 * {{{
 *   (A => Unit) => Cancelable
 * }}}
 *
 * This is approximately the signature of JavaScript's `setTimeout`,
 * which will return a "task ID" that can be used to cancel it.
 *
 * N.B. this type class in particular is NOT describing cancelable
 * async processes, see the [[Concurrent]] type class for that.
 *
 * ==Async Type class==
 *
 * This type class allows the modeling of data types that:
 *
 *  1. can start asynchronous processes
 *  1. can emit one result on completion
 *  1. can end in error
 *
 * N.B. on the "one result" signaling, this is not an ''exactly once''
 * requirement. At this point streaming types can implement `Async`
 * and such an ''exactly once'' requirement is only clear in [[Effect]].
 *
 * Therefore the signature exposed by the [[Async!.async async]]
 * builder is this:
 *
 * {{{
 *   (Either[Throwable, A] => Unit) => Unit
 * }}}
 *
 * N.B. such asynchronous processes are not cancelable.
 * See the [[Concurrent]] alternative for that.
 */
@typeclass
@implicitNotFound("""Cannot find implicit value for Async[${F}].
Building this implicit value might depend on having an implicit
s.c.ExecutionContext in scope, a Scheduler, a ContextShift[${F}]
or some equivalent type.""")
trait Async[F[_]] extends Sync[F] with LiftIO[F] {

  /**
   * Creates a simple, non-cancelable `F[A]` instance that
   * executes an asynchronous process on evaluation.
   *
   * The given function is being injected with a side-effectful
   * callback for signaling the final result of an asynchronous
   * process.
   *
   * This operation could be derived from [[asyncF]], because:
   *
   * {{{
   *   F.async(k) <-> F.asyncF(cb => F.delay(k(cb)))
   * }}}
   *
   * As an example of wrapping an impure async API, here's the
   * implementation of [[Async.shift]]:
   *
   * {{{
   *   def shift[F[_]](ec: ExecutionContext)(implicit F: Async[F]): F[Unit] =
   *     F.async { cb =>
   *       // Scheduling an async boundary (logical thread fork)
   *       ec.execute(new Runnable {
   *         def run(): Unit = {
   *           // Signaling successful completion
   *           cb(Right(()))
   *         }
   *       })
   *     }
   * }}}
   *
   * @see [[asyncF]] for the variant that can suspend side effects
   *      in the provided registration function.
   *
   * @param k is a function that should be called with a
   *       callback for signaling the result once it is ready
   */
  def async[A](k: (Either[Throwable, A] => Unit) => Unit): F[A]

  /**
   * Creates a simple, non-cancelable `F[A]` instance that
   * executes an asynchronous process on evaluation.
   *
   * The given function is being injected with a side-effectful
   * callback for signaling the final result of an asynchronous
   * process. And its returned result needs to be a pure `F[Unit]`
   * that gets evaluated by the runtime.
   *
   * Note the simpler async variant [[async]] can be derived like this:
   *
   * {{{
   *   F.async(k) <-> F.asyncF(cb => F.delay(k(cb)))
   * }}}
   *
   * For wrapping impure APIs usually you can use the simpler [[async]],
   * however `asyncF` is useful in cases where impure APIs are
   * wrapped with the help of pure abstractions, such as
   * [[cats.effect.concurrent.Ref Ref]].
   *
   * For example here's how a simple, "pure Promise" implementation
   * could be implemented via `Ref` (sample is for didactic purposes,
   * as you have a far better
   * [[cats.effect.concurrent.Deferred Deferred]] available):
   *
   * {{{
   *   import cats.effect.concurrent.Ref
   *
   *   type Callback[-A] = Either[Throwable, A] => Unit
   *
   *   class PurePromise[F[_], A](ref: Ref[F, Either[List[Callback[A]], A]])
   *     (implicit F: Async[F]) {
   *
   *     def get: F[A] = F.asyncF { cb =>
   *       ref.modify {
   *         case current @ Right(result) =>
   *           (current, F.delay(cb(Right(result))))
   *         case Left(list) =>
   *           (Left(cb :: list), F.unit)
   *       }
   *     }
   *
   *     def complete(value: A): F[Unit] =
   *       F.flatten(ref.modify {
   *         case Left(list) =>
   *           (Right(value), F.delay(list.foreach(_(Right(value)))))
   *         case right =>
   *           (right, F.unit)
   *       })
   *   }
   * }}}
   *
   * N.B. if `F[_]` is a cancelable data type (i.e. implementing
   * [[Concurrent]]), then the returned `F[Unit]` can be cancelable,
   * its evaluation hooking into the underlying cancelation mechanism
   * of `F[_]`, so something like this behaves like you'd expect:
   *
   * {{{
   *   def delayed[F[_], A](thunk: => A)
   *     (implicit F: Async[F], timer: Timer[F]): F[A] = {
   *
   *     timer.sleep(1.second) *> F.delay(cb(
   *       try cb(Right(thunk))
   *       catch { case NonFatal(e) => Left(cb(Left(e))) }
   *     ))
   *   }
   * }}}
   *
   * The `asyncF` operation behaves like [[Sync.suspend]], except
   * that the result has to be signaled via the provided callback.
   *
   * ==ERROR HANDLING==
   *
   * As a matter of contract the returned `F[Unit]` should not
   * throw errors. If it does, then the behavior is undefined.
   *
   * This is because by contract the provided callback should
   * only be called once. Calling it concurrently, multiple times,
   * is a contract violation. And if the returned `F[Unit]` throws,
   * then the implementation might have called it already, so it
   * would be a contract violation to call it without expensive
   * synchronization.
   *
   * In case errors are thrown the behavior is implementation specific.
   * The error might get logged to stderr, or via other mechanisms
   * that are implementations specific.
   *
   * @see [[async]] for the simpler variant.
   *
   * @param k is a function that should be called with a
   *       callback for signaling the result once it is ready
   */
  def asyncF[A](k: (Either[Throwable, A] => Unit) => F[Unit]): F[A]

  /**
   * Inherited from [[LiftIO]], defines a conversion from [[IO]]
   * in terms of the `Async` type class.
   *
   * N.B. expressing this conversion in terms of `Async` and its
   * capabilities means that the resulting `F` is not cancelable.
   * [[Concurrent]] then overrides this with an implementation
   * that is.
   *
   * To access this implementation as a standalone function, you can
   * use [[Async$.liftIO Async.liftIO]] (on the object companion).
   */
  override def liftIO[A](ioa: IO[A]): F[A] =
    Async.liftIO(ioa)(this)

  /**
   * Returns a non-terminating `F[_]`, that never completes
   * with a result, being equivalent to `async(_ => ())`
   */
  def never[A]: F[A] = async(_ => ())
}

object Async {

  /**
   * Returns an non-terminating `F[_]`, that never completes
   * with a result, being equivalent with `async(_ => ())`.
   */
  @deprecated("Moved to Async[F]", "0.10")
  def never[F[_], A](implicit F: Async[F]): F[A] =
    F.never

  /**
   * Generic shift operation, defined for any `Async` data type.
   *
   * Shifts the bind continuation onto the specified thread pool.
   * Analogous with [[IO.shift(ec* IO.shift]].
   */
  def shift[F[_]](ec: ExecutionContext)(implicit F: Async[F]): F[Unit] =
    F.async { cb =>
      ec.execute(new Runnable {
        def run(): Unit = cb(Callback.rightUnit)
      })
    }

  def fromFuture[F[_], A](fa: F[Future[A]])(implicit F: Async[F], cs: ContextShift[F]): F[A] =
    F.guarantee(
      fa.flatMap { f =>
        f.value match {
          case Some(result) =>
            result match {
              case Success(a) => F.pure(a)
              case Failure(e) => F.raiseError[A](e)
            }
          case _ =>
            F.async[A] { cb =>
              f.onComplete { r =>
                cb(r match {
                  case Success(a) => Right(a)
                  case Failure(e) => Left(e)
                })
              }(immediate)
            }
        }
      }
    )(cs.shift)

  /**
   * Lifts any `IO` value into any data type implementing [[Async]].
   *
   * This is the default `Async.liftIO` implementation.
   */
  def liftIO[F[_], A](io: IO[A])(implicit F: Async[F]): F[A] =
    io match {
      case Pure(a)       => F.pure(a)
      case RaiseError(e) => F.raiseError(e)
      case Delay(thunk)  => F.delay(thunk())
      case _ =>
        F.suspend {
          IORunLoop.step(io) match {
            case Pure(a)       => F.pure(a)
            case RaiseError(e) => F.raiseError(e)
            case async         => F.async(async.unsafeRunAsync)
          }
        }
    }

  /**
   * Lazily memoizes `f`. For every time the returned `F[F[A]]` is
   * bound, the effect `f` will be performed at most once (when the
   * inner `F[A]` is bound the first time).
   *
   * Note: This version of `memoize` does not support interruption.
   * Use `Concurrent.memoize` if you need that.
   */
  def memoize[F[_], A](f: F[A])(implicit F: Async[F]): F[F[A]] =
    Ref.of[F, Option[Deferred[F, Either[Throwable, A]]]](None).map { ref =>
      Deferred.uncancelable[F, Either[Throwable, A]].flatMap { d =>
        ref
          .modify {
            case None =>
              Some(d) -> f.attempt.flatTap(d.complete)
            case s @ Some(other) =>
              s -> other.get
          }
          .flatten
          .rethrow
      }
    }

  /**
   * Like `Parallel.parTraverse`, but limits the degree of parallelism.
   */
  def parTraverseN[T[_]: Traverse, M[_], A, B](n: Long)(ta: T[A])(f: A => M[B])(implicit M: Async[M],
                                                                                P: Parallel[M]): M[T[B]] =
    for {
      semaphore <- Semaphore.uncancelable(n)(M)
      tb <- ta.parTraverse { a =>
        semaphore.withPermit(f(a))
      }
    } yield tb

  /**
   * Like `Parallel.parSequence`, but limits the degree of parallelism.
   */
  def parSequenceN[T[_]: Traverse, M[_], A](n: Long)(tma: T[M[A]])(implicit M: Async[M], P: Parallel[M]): M[T[A]] =
    for {
      semaphore <- Semaphore.uncancelable(n)(M)
      mta <- tma.map(semaphore.withPermit).parSequence
    } yield mta

  /**
   * [[Async]] instance built for `cats.data.EitherT` values initialized
   * with any `F` data type that also implements `Async`.
   */
  implicit def catsEitherTAsync[F[_]: Async, L]: Async[EitherT[F, L, *]] =
    new EitherTAsync[F, L] { def F = Async[F] }

  /**
   * [[Async]] instance built for `cats.data.OptionT` values initialized
   * with any `F` data type that also implements `Async`.
   */
  implicit def catsOptionTAsync[F[_]: Async]: Async[OptionT[F, *]] =
    new OptionTAsync[F] { def F = Async[F] }

  /**
   * [[Async]] instance built for `cats.data.StateT` values initialized
   * with any `F` data type that also implements `Async`.
   */
  implicit def catsStateTAsync[F[_]: Async, S]: Async[StateT[F, S, *]] =
    new StateTAsync[F, S] { def F = Async[F] }

  /**
   * [[Async]] instance built for `cats.data.WriterT` values initialized
   * with any `F` data type that also implements `Async`.
   */
  implicit def catsWriterTAsync[F[_]: Async, L: Monoid]: Async[WriterT[F, L, *]] =
    new WriterTAsync[F, L] { def F = Async[F]; def L = Monoid[L] }

  /**
   * [[Async]] instance built for `cats.data.Kleisli` values initialized
   * with any `F` data type that also implements `Async`.
   */
  implicit def catsKleisliAsync[F[_]: Async, R]: Async[Kleisli[F, R, *]] =
    new KleisliAsync[F, R] { def F = Async[F]; }

  /**
   * [[Async]] instance built for `cats.data.IorT` values initialized
   * with any `F` data type that also implements `Async`.
   */
  implicit def catsIorTAsync[F[_]: Async, L: Semigroup]: Async[IorT[F, L, *]] =
    new IorTAsync[F, L] { def F = Async[F]; def L = Semigroup[L] }

  /**
   * [[Async]] instance built for `cats.data.ReaderWriterStateT` values initialized
   * with any `F` data type that also implements `Async`.
   */
  implicit def ReaderWriterStateTAsync[F[_]: Async, E, L: Monoid, S]: Async[ReaderWriterStateT[F, E, L, S, *]] =
    new ReaderWriterStateTAsync[F, E, L, S] { def F = Async[F]; def L = Monoid[L] }

  private[effect] trait EitherTAsync[F[_], L]
      extends Async[EitherT[F, L, *]]
      with Sync.EitherTSync[F, L]
      with LiftIO.EitherTLiftIO[F, L] {
    implicit override protected def F: Async[F]
    protected def FF = F

    override def asyncF[A](k: (Either[Throwable, A] => Unit) => EitherT[F, L, Unit]): EitherT[F, L, A] =
      EitherT.liftF(F.asyncF(cb => F.as(k(cb).value, ())))

    override def async[A](k: (Either[Throwable, A] => Unit) => Unit): EitherT[F, L, A] =
      EitherT.liftF(F.async(k))
  }

  private[effect] trait OptionTAsync[F[_]]
      extends Async[OptionT[F, *]]
      with Sync.OptionTSync[F]
      with LiftIO.OptionTLiftIO[F] {
    implicit override protected def F: Async[F]
    protected def FF = F

    override def asyncF[A](k: (Either[Throwable, A] => Unit) => OptionT[F, Unit]): OptionT[F, A] =
      OptionT.liftF(F.asyncF(cb => F.as(k(cb).value, ())))

    override def async[A](k: (Either[Throwable, A] => Unit) => Unit): OptionT[F, A] =
      OptionT.liftF(F.async(k))
  }

  private[effect] trait StateTAsync[F[_], S]
      extends Async[StateT[F, S, *]]
      with Sync.StateTSync[F, S]
      with LiftIO.StateTLiftIO[F, S] {
    implicit override protected def F: Async[F]
    protected def FA = F

    override def asyncF[A](k: (Either[Throwable, A] => Unit) => StateT[F, S, Unit]): StateT[F, S, A] =
      StateT(s => F.map(F.asyncF[A](cb => k(cb).runA(s)))(a => (s, a)))

    override def async[A](k: (Either[Throwable, A] => Unit) => Unit): StateT[F, S, A] =
      StateT.liftF(F.async(k))
  }

  private[effect] trait WriterTAsync[F[_], L]
      extends Async[WriterT[F, L, *]]
      with Sync.WriterTSync[F, L]
      with LiftIO.WriterTLiftIO[F, L] {
    implicit override protected def F: Async[F]
    protected def FA = F

    override def asyncF[A](k: (Either[Throwable, A] => Unit) => WriterT[F, L, Unit]): WriterT[F, L, A] =
      WriterT.liftF(F.asyncF(cb => F.as(k(cb).run, ())))

    override def async[A](k: (Either[Throwable, A] => Unit) => Unit): WriterT[F, L, A] =
      WriterT.liftF(F.async(k))(L, FA)
  }

  abstract private[effect] class KleisliAsync[F[_], R] extends Sync.KleisliSync[F, R] with Async[Kleisli[F, R, *]] {
    implicit override protected def F: Async[F]

    override def asyncF[A](k: (Either[Throwable, A] => Unit) => Kleisli[F, R, Unit]): Kleisli[F, R, A] =
      Kleisli(a => F.asyncF(cb => k(cb).run(a)))

    override def async[A](k: (Either[Throwable, A] => Unit) => Unit): Kleisli[F, R, A] =
      Kleisli.liftF(F.async(k))
  }

  private[effect] trait IorTAsync[F[_], L]
      extends Async[IorT[F, L, *]]
      with Sync.IorTSync[F, L]
      with LiftIO.IorTLiftIO[F, L] {
    implicit override protected def F: Async[F]
    protected def FA = F

    override def asyncF[A](k: (Either[Throwable, A] => Unit) => IorT[F, L, Unit]): IorT[F, L, A] =
      IorT.liftF(F.asyncF(cb => F.as(k(cb).value, ())))

    override def async[A](k: (Either[Throwable, A] => Unit) => Unit): IorT[F, L, A] =
      IorT.liftF(F.async(k))
  }

  private[effect] trait ReaderWriterStateTAsync[F[_], E, L, S]
      extends Async[ReaderWriterStateT[F, E, L, S, *]]
      with LiftIO.ReaderWriterStateTLiftIO[F, E, L, S]
      with Sync.ReaderWriterStateTSync[F, E, L, S] {
    implicit override protected def F: Async[F]
    protected def FA = F

    override def asyncF[A](
      k: (Either[Throwable, A] => Unit) => ReaderWriterStateT[F, E, L, S, Unit]
    ): ReaderWriterStateT[F, E, L, S, A] =
      ReaderWriterStateT((e, s) =>
        F.map(F.asyncF((cb: Either[Throwable, A] => Unit) => F.as(k(cb).run(e, s), ())))(a => (L.empty, s, a))
      )

    override def async[A](k: (Either[Throwable, A] => Unit) => Unit): ReaderWriterStateT[F, E, L, S, A] =
      ReaderWriterStateT.liftF(F.async(k))
  }
}
