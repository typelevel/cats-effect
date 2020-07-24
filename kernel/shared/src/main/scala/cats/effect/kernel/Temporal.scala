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

package cats.effect.kernel

import cats.{MonadError, Monoid, Semigroup}
import cats.data.{EitherT, IorT, Kleisli, OptionT, WriterT}

import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration

trait Temporal[F[_], E] extends Concurrent[F, E] with Clock[F] {
  // (sleep(n) *> now) <-> now.map(_ + n + d) forSome { val d: Double }
  def sleep(time: FiniteDuration): F[Unit]

  /**
   * Returns an effect that either completes with the result of the source within
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
   */
  def timeoutTo[A](fa: F[A], duration: FiniteDuration, fallback: F[A]): F[A] =
    flatMap(race(fa, sleep(duration))) {
      case Left(a) => pure(a)
      case Right(_) => fallback
    }

  /**
   * Returns an effect that either completes with the result of the source within
   * the specified time `duration` or otherwise raises a `TimeoutException`.
   *
   * The source is cancelled in the event that it takes longer than
   * the specified time duration to complete.
   *
   * @param duration is the time span for which we wait for the source to
   *        complete; in the event that the specified time has passed without
   *        the source completing, a `TimeoutException` is raised
   */
  def timeout[A](fa: F[A], duration: FiniteDuration)(
      implicit ev: TimeoutException <:< E): F[A] = {
    val timeoutException = raiseError[A](ev(new TimeoutException(duration.toString)))
    timeoutTo(fa, duration, timeoutException)
  }

}

object Temporal {
  def apply[F[_], E](implicit F: Temporal[F, E]): F.type = F
  def apply[F[_]](implicit F: Temporal[F, _], d: DummyImplicit): F.type = F

  implicit def temporalForOptionT[F[_], E](
      implicit F0: Temporal[F, E]): Temporal[OptionT[F, *], E] =
    new OptionTTemporal[F, E] {
      override implicit protected def F: Temporal[F, E] = F0
    }

  implicit def temporalForEitherT[F[_], E0, E](
      implicit F0: Temporal[F, E]): Temporal[EitherT[F, E0, *], E] =
    new EitherTTemporal[F, E0, E] {
      override implicit protected def F: Temporal[F, E] = F0
    }

  implicit def temporalForKleisli[F[_], R, E](
      implicit F0: Temporal[F, E]): Temporal[Kleisli[F, R, *], E] =
    new KleisliTemporal[F, R, E] {
      override implicit protected def F: Temporal[F, E] = F0
    }

  implicit def temporalForIorT[F[_], L, E](
      implicit F0: Temporal[F, E],
      L0: Semigroup[L]): Temporal[IorT[F, L, *], E] =
    new IorTTemporal[F, L, E] {
      override implicit protected def F: Temporal[F, E] = F0

      override implicit protected def L: Semigroup[L] = L0
    }

  implicit def temporalForWriterT[F[_], L, E](
      implicit F0: Temporal[F, E],
      L0: Monoid[L]): Temporal[WriterT[F, L, *], E] =
    new WriterTTemporal[F, L, E] {
      override implicit protected def F: Temporal[F, E] = F0

      override implicit protected def L: Monoid[L] = L0
    }

  trait OptionTTemporal[F[_], E]
      extends Temporal[OptionT[F, *], E]
      with Concurrent.OptionTConcurrent[F, E]
      with Clock.OptionTClock[F] {

    implicit protected def F: Temporal[F, E]

    override def delegate: MonadError[OptionT[F, *], E] =
      OptionT.catsDataMonadErrorForOptionT[F, E]

    def sleep(time: FiniteDuration): OptionT[F, Unit] = OptionT.liftF(F.sleep(time))

  }

  trait EitherTTemporal[F[_], E0, E]
      extends Temporal[EitherT[F, E0, *], E]
      with Concurrent.EitherTConcurrent[F, E0, E]
      with Clock.EitherTClock[F, E0] {

    implicit protected def F: Temporal[F, E]

    override def delegate: MonadError[EitherT[F, E0, *], E] =
      EitherT.catsDataMonadErrorFForEitherT[F, E, E0]

    def sleep(time: FiniteDuration): EitherT[F, E0, Unit] = EitherT.liftF(F.sleep(time))
  }

  trait IorTTemporal[F[_], L, E]
      extends Temporal[IorT[F, L, *], E]
      with Concurrent.IorTConcurrent[F, L, E]
      with Clock.IorTClock[F, L] {

    implicit protected def F: Temporal[F, E]

    override def delegate: MonadError[IorT[F, L, *], E] =
      IorT.catsDataMonadErrorFForIorT[F, L, E]

    def sleep(time: FiniteDuration): IorT[F, L, Unit] = IorT.liftF(F.sleep(time))
  }

  trait WriterTTemporal[F[_], L, E]
      extends Temporal[WriterT[F, L, *], E]
      with Concurrent.WriterTConcurrent[F, L, E]
      with Clock.WriterTClock[F, L] {

    implicit protected def F: Temporal[F, E]

    implicit protected def L: Monoid[L]

    override def delegate: MonadError[WriterT[F, L, *], E] =
      WriterT.catsDataMonadErrorForWriterT[F, L, E]

    def sleep(time: FiniteDuration): WriterT[F, L, Unit] = WriterT.liftF(F.sleep(time))
  }

  trait KleisliTemporal[F[_], R, E]
      extends Temporal[Kleisli[F, R, *], E]
      with Concurrent.KleisliConcurrent[F, R, E]
      with Clock.KleisliClock[F, R] {

    implicit protected def F: Temporal[F, E]

    override def delegate: MonadError[Kleisli[F, R, *], E] =
      Kleisli.catsDataMonadErrorForKleisli[F, R, E]

    def sleep(time: FiniteDuration): Kleisli[F, R, Unit] = Kleisli.liftF(F.sleep(time))
  }

}
