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

package cats.effect.kernel

import cats.data._
import cats.{Applicative, MonadError, Monoid, Semigroup}
import cats.syntax.all._

import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

/**
 * A typeclass that encodes the notion of suspending fibers for a given duration. Analogous to
 * `Thread.sleep` but is only semantically blocking rather than blocking an underlying OS
 * pthread.
 */
trait GenTemporal[F[_], E] extends GenConcurrent[F, E] with Clock[F] {
  override def applicative: Applicative[F] = this

  /**
   * Semantically block the fiber for the specified duration.
   *
   * @param time
   *   The duration to semantically block for
   */
  def sleep(time: FiniteDuration): F[Unit]

  /**
   * Delay the execution of `fa` by a given duration.
   *
   * @param fa
   *   The effect to execute
   *
   * @param time
   *   The duration to wait before executing fa
   */
  def delayBy[A](fa: F[A], time: FiniteDuration): F[A] =
    productR(sleep(time))(fa)

  /**
   * Wait for the specified duration after the execution of `fa` before returning the result.
   *
   * @param fa
   *   The effect to execute
   * @param time
   *   The duration to wait after executing fa
   */
  def andWait[A](fa: F[A], time: FiniteDuration): F[A] =
    productL(fa)(sleep(time))

  /**
   * Returns an effect that either completes with the result of the source within the specified
   * time `duration` or otherwise evaluates the `fallback`.
   *
   * The source is canceled in the event that it takes longer than the specified time duration
   * to complete. Once the source has been successfully canceled (and has completed its
   * finalizers), the fallback will be sequenced. If the source is uncancelable, the resulting
   * effect will wait for it to complete before evaluating the fallback.
   *
   * @param duration
   *   The time span for which we wait for the source to complete; in the event that the
   *   specified time has passed without the source completing, the `fallback` gets evaluated
   *
   * @param fallback
   *   The task evaluated after the duration has passed and the source canceled
   */
  def timeoutTo[A](fa: F[A], duration: FiniteDuration, fallback: F[A]): F[A] =
    flatMap(race(fa, sleep(duration))) {
      case Left(a) => pure(a)
      case Right(_) => fallback
    }

  /**
   * Returns an effect that either completes with the result of the source within the specified
   * time `duration` or otherwise raises a `TimeoutException`.
   *
   * The source is canceled in the event that it takes longer than the specified time duration
   * to complete. Once the source has been successfully canceled (and has completed its
   * finalizers), the `TimeoutException` will be raised. If the source is uncancelable, the
   * resulting effect will wait for it to complete before raising the exception.
   *
   * @param duration
   *   The time span for which we wait for the source to complete; in the event that the
   *   specified time has passed without the source completing, a `TimeoutException` is raised
   */
  def timeout[A](fa: F[A], duration: FiniteDuration)(
      implicit ev: TimeoutException <:< E): F[A] = {
    flatMap(race(fa, sleep(duration))) {
      case Left(a) => pure(a)
      case Right(_) => raiseError[A](ev(new TimeoutException(duration.toString())))
    }
  }

  /**
   * Returns an effect that either completes with the result of the source within the specified
   * time `duration` or otherwise raises a `TimeoutException`.
   *
   * The source is canceled in the event that it takes longer than the specified time duration
   * to complete. Unlike [[timeout]], the cancelation of the source will be ''requested'' but
   * not awaited, and the exception will be raised immediately upon the completion of the timer.
   * This may more closely match intuitions about timeouts, but it also violates backpressure
   * guarantees and intentionally leaks fibers.
   *
   * This combinator should be applied very carefully.
   *
   * @param duration
   *   The time span for which we wait for the source to complete; in the event that the
   *   specified time has passed without the source completing, a `TimeoutException` is raised
   * @see
   *   [[timeout]] for a variant which respects backpressure and does not leak fibers
   */
  def timeoutAndForget[A](fa: F[A], duration: FiniteDuration)(
      implicit ev: TimeoutException <:< E): F[A] =
    uncancelable { poll =>
      implicit val F: GenTemporal[F, E] = this

      racePair(fa, sleep(duration)) flatMap {
        case Left((oc, f)) =>
          poll(f.cancel *> oc.embedNever)

        case Right((f, _)) =>
          start(f.cancel) *> raiseError[A](ev(new TimeoutException(duration.toString)))
      }
    }
}

object GenTemporal {
  def apply[F[_], E](implicit F: GenTemporal[F, E]): F.type = F
  def apply[F[_]](implicit F: GenTemporal[F, _], d: DummyImplicit): F.type = F

  implicit def genTemporalForOptionT[F[_], E](
      implicit F0: GenTemporal[F, E]): GenTemporal[OptionT[F, *], E] =
    new OptionTTemporal[F, E] {
      override implicit protected def F: GenTemporal[F, E] = F0
    }

  implicit def genTemporalForEitherT[F[_], E0, E](
      implicit F0: GenTemporal[F, E]): GenTemporal[EitherT[F, E0, *], E] =
    new EitherTTemporal[F, E0, E] {
      override implicit protected def F: GenTemporal[F, E] = F0
    }

  implicit def genTemporalForKleisli[F[_], R, E](
      implicit F0: GenTemporal[F, E]): GenTemporal[Kleisli[F, R, *], E] =
    new KleisliTemporal[F, R, E] {
      override implicit protected def F: GenTemporal[F, E] = F0
    }

  implicit def genTemporalForIorT[F[_], L, E](
      implicit F0: GenTemporal[F, E],
      L0: Semigroup[L]): GenTemporal[IorT[F, L, *], E] =
    new IorTTemporal[F, L, E] {
      override implicit protected def F: GenTemporal[F, E] = F0

      override implicit protected def L: Semigroup[L] = L0
    }

  implicit def genTemporalForWriterT[F[_], L, E](
      implicit F0: GenTemporal[F, E],
      L0: Monoid[L]): GenTemporal[WriterT[F, L, *], E] =
    new WriterTTemporal[F, L, E] {
      override implicit protected def F: GenTemporal[F, E] = F0

      override implicit protected def L: Monoid[L] = L0
    }

  private[kernel] trait OptionTTemporal[F[_], E]
      extends GenTemporal[OptionT[F, *], E]
      with GenConcurrent.OptionTGenConcurrent[F, E]
      with Clock.OptionTClock[F] {

    implicit protected def F: GenTemporal[F, E]
    protected def C = F

    override protected def delegate: MonadError[OptionT[F, *], E] =
      OptionT.catsDataMonadErrorForOptionT[F, E]

    def sleep(time: FiniteDuration): OptionT[F, Unit] = OptionT.liftF(F.sleep(time))

  }

  private[kernel] trait EitherTTemporal[F[_], E0, E]
      extends GenTemporal[EitherT[F, E0, *], E]
      with GenConcurrent.EitherTGenConcurrent[F, E0, E]
      with Clock.EitherTClock[F, E0] {

    implicit protected def F: GenTemporal[F, E]
    protected def C = F

    override protected def delegate: MonadError[EitherT[F, E0, *], E] =
      EitherT.catsDataMonadErrorFForEitherT[F, E, E0]

    def sleep(time: FiniteDuration): EitherT[F, E0, Unit] = EitherT.liftF(F.sleep(time))
  }

  private[kernel] trait IorTTemporal[F[_], L, E]
      extends GenTemporal[IorT[F, L, *], E]
      with GenConcurrent.IorTGenConcurrent[F, L, E]
      with Clock.IorTClock[F, L] {

    implicit protected def F: GenTemporal[F, E]
    protected def C = F

    override protected def delegate: MonadError[IorT[F, L, *], E] =
      IorT.catsDataMonadErrorFForIorT[F, L, E]

    def sleep(time: FiniteDuration): IorT[F, L, Unit] = IorT.liftF(F.sleep(time))
  }

  private[kernel] trait WriterTTemporal[F[_], L, E]
      extends GenTemporal[WriterT[F, L, *], E]
      with GenConcurrent.WriterTGenConcurrent[F, L, E]
      with Clock.WriterTClock[F, L] {

    implicit protected def F: GenTemporal[F, E]
    protected def C = F

    implicit protected def L: Monoid[L]

    override protected def delegate: MonadError[WriterT[F, L, *], E] =
      WriterT.catsDataMonadErrorForWriterT[F, L, E]

    def sleep(time: FiniteDuration): WriterT[F, L, Unit] = WriterT.liftF(F.sleep(time))
  }

  private[kernel] trait KleisliTemporal[F[_], R, E]
      extends GenTemporal[Kleisli[F, R, *], E]
      with GenConcurrent.KleisliGenConcurrent[F, R, E]
      with Clock.KleisliClock[F, R] {

    implicit protected def F: GenTemporal[F, E]
    protected def C = F

    override protected def delegate: MonadError[Kleisli[F, R, *], E] =
      Kleisli.catsDataMonadErrorForKleisli[F, R, E]

    def sleep(time: FiniteDuration): Kleisli[F, R, Unit] = Kleisli.liftF(F.sleep(time))
  }

}
