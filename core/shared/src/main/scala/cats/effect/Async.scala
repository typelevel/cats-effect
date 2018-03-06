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

import simulacrum._
import cats.data.{EitherT, OptionT, StateT, WriterT}
import cats.effect.IO.{Delay, Pure, RaiseError}
import cats.effect.internals.{Callback, IORunLoop}

import scala.annotation.implicitNotFound
import scala.concurrent.ExecutionContext
import scala.util.Either

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
s.c.ExecutionContext in scope, a Scheduler or some equivalent type.""")
trait Async[F[_]] extends Sync[F] with LiftIO[F] {
  /**
   * Creates a simple, noncancelable `F[A]` instance that
   * executes an asynchronous process on evaluation.
   *
   * The given function is being injected with a side-effectful
   * callback for signaling the final result of an asynchronous
   * process.
   *
   * @param k is a function that should be called with a
   *       callback for signaling the result once it is ready
   */
  def async[A](k: (Either[Throwable, A] => Unit) => Unit): F[A]

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
   * DEPRECATED — moved to [[Async$.shift]].
   *
   * The reason for the deprecation is that there's no potential
   * for optimisations in implementing instances, so this operation
   * can be a simple utility.
   *
   * It's also a lawless operation that might be better served
   * with a concrete, non-polymorphic implementation.
   */
  @deprecated("Moved to Async$.shift, will be removed in 1.0", "0.10")
  private[effect] def shift(ec: ExecutionContext): F[Unit] = {
    // $COVERAGE-OFF$
    Async.shift(ec)(this)
    // $COVERAGE-ON$
  }
}

object Async {
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

  /**
   * Lifts any `IO` value into any data type implementing [[Async]].
   *
   * This is the default `Async.liftIO` implementation.
   */
  def liftIO[F[_], A](io: IO[A])(implicit F: Async[F]): F[A] =
    io match {
      case Pure(a) => F.pure(a)
      case RaiseError(e) => F.raiseError(e)
      case Delay(thunk) => F.delay(thunk())
      case _ =>
        F.suspend {
          IORunLoop.step(io) match {
            case Pure(a) => F.pure(a)
            case RaiseError(e) => F.raiseError(e)
            case async => F.async(async.unsafeRunAsync)
          }
        }
    }

  /**
   * [[Async]] instance built for `cats.data.EitherT` values initialized
   * with any `F` data type that also implements `Async`.
   */
  implicit def catsEitherTAsync[F[_]: Async, L]: Async[EitherT[F, L, ?]] =
    new EitherTAsync[F, L] { def F = Async[F] }

  /**
   * [[Async]] instance built for `cats.data.OptionT` values initialized
   * with any `F` data type that also implements `Async`.
   */
  implicit def catsOptionTAsync[F[_]: Async]: Async[OptionT[F, ?]] =
    new OptionTAsync[F] { def F = Async[F] }

  /**
   * [[Async]] instance built for `cats.data.StateT` values initialized
   * with any `F` data type that also implements `Async`.
   */
  implicit def catsStateTAsync[F[_]: Async, S]: Async[StateT[F, S, ?]] =
    new StateTAsync[F, S] { def F = Async[F] }

  /**
   * [[Async]] instance built for `cats.data.WriterT` values initialized
   * with any `F` data type that also implements `Async`.
   */
  implicit def catsWriterTAsync[F[_]: Async, L: Monoid]: Async[WriterT[F, L, ?]] =
    new WriterTAsync[F, L] { def F = Async[F]; def L = Monoid[L] }

  private[effect] trait EitherTAsync[F[_], L] extends Async[EitherT[F, L, ?]]
    with Sync.EitherTSync[F, L]
    with LiftIO.EitherTLiftIO[F, L] {

    override implicit protected def F: Async[F]
    protected def FF = F

    def async[A](k: (Either[Throwable, A] => Unit) => Unit): EitherT[F, L, A] =
      EitherT.liftF(F.async(k))
  }

  private[effect] trait OptionTAsync[F[_]] extends Async[OptionT[F, ?]]
    with Sync.OptionTSync[F]
    with LiftIO.OptionTLiftIO[F] {

    override protected implicit def F: Async[F]
    protected def FF = F

    def async[A](k: (Either[Throwable, A] => Unit) => Unit): OptionT[F, A] =
      OptionT.liftF(F.async(k))
  }

  private[effect] trait StateTAsync[F[_], S] extends Async[StateT[F, S, ?]]
    with Sync.StateTSync[F, S]
    with LiftIO.StateTLiftIO[F, S] {

    override protected implicit def F: Async[F]
    protected def FA = F

    def async[A](k: (Either[Throwable, A] => Unit) => Unit): StateT[F, S, A] =
      StateT.liftF(F.async(k))
  }

  private[effect] trait WriterTAsync[F[_], L] extends Async[WriterT[F, L, ?]]
    with Sync.WriterTSync[F, L]
    with LiftIO.WriterTLiftIO[F, L] {

    override protected implicit def F: Async[F]
    protected def FA = F

    def async[A](k: (Either[Throwable, A] => Unit) => Unit): WriterT[F, L, A] =
      WriterT.liftF(F.async(k))(L, FA)
  }
}
