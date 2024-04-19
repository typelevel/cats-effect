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

import cats.{Applicative, MonadError, Monoid, Semigroup}
import cats.data._
import cats.effect.kernel.GenTemporal.handleDuration
import cats.syntax.all._

import scala.concurrent.TimeoutException
import scala.concurrent.duration.{Duration, FiniteDuration}

/**
 * A typeclass that encodes the notion of suspending fibers for a given duration. Analogous to
 * `Thread.sleep` but is only fiber blocking rather than blocking an underlying OS pthread.
 */
trait GenTemporal[F[_], E] extends GenConcurrent[F, E] with Clock[F] {
  override def applicative: Applicative[F] = this

  /**
   * Semantically block the fiber for the specified duration.
   *
   * @param time
   *   The duration to semantically block for
   */
  def sleep(time: Duration): F[Unit] =
    handleDuration[F[Unit]](time, never)(sleep(_))

  protected def sleep(time: FiniteDuration): F[Unit]

  /**
   * Delay the execution of `fa` by a given duration.
   *
   * @param fa
   *   The effect to execute
   *
   * @param time
   *   The duration to wait before executing fa
   */
  def delayBy[A](fa: F[A], time: Duration): F[A] =
    handleDuration[F[A]](time, never)(delayBy(fa, _))

  protected def delayBy[A](fa: F[A], time: FiniteDuration): F[A] =
    productR(sleep(time))(fa)

  /**
   * Wait for the specified duration after the execution of `fa` before returning the result.
   *
   * @param fa
   *   The effect to execute
   * @param time
   *   The duration to wait after executing fa
   */
  def andWait[A](fa: F[A], time: Duration): F[A] =
    handleDuration(time, productL(fa)(never))(andWait(fa, _))

  protected def andWait[A](fa: F[A], time: FiniteDuration): F[A] =
    productL(fa)(sleep(time))

  /**
   * Returns an effect that either completes with the result of the source or otherwise
   * evaluates the `fallback`.
   *
   * The source is raised against the timeout `duration`, and its cancelation is triggered if
   * the source doesn't complete within the specified time. The resulting effect will always
   * wait for the source effect to complete (and to complete its finalizers), and will return
   * the source's outcome over sequencing the `fallback`.
   *
   * In case source and timeout complete simultaneously, the result of the source will be
   * returned over sequencing the `fallback`.
   *
   * If the source in uncancelable, `fallback` will never be evaluated.
   *
   * @param duration
   *   The time span for which we wait for the source to complete before triggering its
   *   cancelation; in the event that the specified time has passed without the source
   *   completing, the `fallback` gets evaluated
   *
   * @param fallback
   *   The task evaluated after the duration has passed and the source canceled
   */
  def timeoutTo[A](fa: F[A], duration: Duration, fallback: F[A]): F[A] =
    handleDuration(duration, fa)(timeoutTo(fa, _, fallback))

  protected def timeoutTo[A](fa: F[A], duration: FiniteDuration, fallback: F[A]): F[A] =
    uncancelable { poll =>
      implicit val F: GenTemporal[F, E] = this

      poll(racePair(fa, sleep(duration))) flatMap {
        case Left((oc, f)) => f.cancel *> oc.embed(poll(F.canceled) *> F.never)
        case Right((f, _)) => f.cancel *> f.join.flatMap { oc => oc.embed(fallback) }
      }
    }

  /**
   * Returns an effect that either completes with the result of the source or raises a
   * `TimeoutException`.
   *
   * The source is raced against the timeout `duration`, and its cancelation is triggered if the
   * source doesn't complete within the specified time. The resulting effect will always wait
   * for the source effect to complete (and to complete its finalizers), and will return the
   * source's outcome over raising a `TimeoutException`.
   *
   * In case source and timeout complete simultaneously, the result of the source will be
   * returned over raising a `TimeoutException`.
   *
   * If the source effect is uncancelable, a `TimeoutException` will never be raised.
   *
   * @param duration
   *   The time span for which we wait for the source to complete before triggering its
   *   cancelation; in the event that the specified time has passed without the source
   *   completing, a `TimeoutException` is raised
   * @see
   *   [[timeoutAndForget[A](fa:F[A],duration:scala\.concurrent\.duration\.Duration)* timeoutAndForget]]
   *   for a variant which does not wait for cancelation of the source effect to complete.
   */
  def timeout[A](fa: F[A], duration: Duration)(implicit ev: TimeoutException <:< E): F[A] = {
    handleDuration(duration, fa)(timeout(fa, _))
  }

  protected def timeout[A](fa: F[A], duration: FiniteDuration)(
      implicit ev: TimeoutException <:< E): F[A] = {
    uncancelable { poll =>
      implicit val F: GenTemporal[F, E] = this

      poll(racePair(fa, sleep(duration))) flatMap {
        case Left((oc, f)) => f.cancel *> oc.embed(poll(F.canceled) *> F.never)
        case Right((f, _)) =>
          f.cancel *> f.join.flatMap { oc =>
            oc.embed(raiseError[A](ev(new TimeoutException(duration.toString()))))
          }
      }
    }
  }

  /**
   * Returns an effect that either completes with the result of the source within the specified
   * time `duration` or otherwise raises a `TimeoutException`.
   *
   * The source is canceled in the event that it takes longer than the specified time duration
   * to complete. Unlike
   * [[timeout[A](fa:F[A],duration:scala\.concurrent\.duration\.Duration)* timeout]], the
   * cancelation of the source will be ''requested'' but not awaited, and the exception will be
   * raised immediately upon the completion of the timer. This may more closely match intuitions
   * about timeouts, but it also violates backpressure guarantees and intentionally leaks
   * fibers.
   *
   * This combinator should be applied very carefully.
   *
   * @param duration
   *   The time span for which we wait for the source to complete; in the event that the
   *   specified time has passed without the source completing, a `TimeoutException` is raised
   * @see
   *   [[timeout[A](fa:F[A],duration:scala\.concurrent\.duration\.Duration)* timeout]] for a
   *   variant which respects backpressure and does not leak fibers
   */
  def timeoutAndForget[A](fa: F[A], duration: Duration)(
      implicit ev: TimeoutException <:< E): F[A] = {
    handleDuration(duration, fa)(timeoutAndForget(fa, _))
  }

  protected def timeoutAndForget[A](fa: F[A], duration: FiniteDuration)(
      implicit ev: TimeoutException <:< E): F[A] =
    uncancelable { poll =>
      implicit val F: GenTemporal[F, E] = this

      poll(racePair(fa, sleep(duration))) flatMap {
        case Left((oc, f)) =>
          poll(f.cancel *> oc.embed(poll(F.canceled) *> F.never))

        case Right((f, _)) =>
          start(f.cancel) *> raiseError[A](ev(new TimeoutException(duration.toString)))
      }
    }

  /**
   * Returns a nested effect which returns the time in a much faster way than
   * `Clock[F]#realTime`. This is achieved by caching the real time when the outer effect is run
   * and, when the inner effect is run, the offset is used in combination with
   * `Clock[F]#monotonic` to give an approximation of the real time. The practical benefit of
   * this is a reduction in the number of syscalls, since `realTime` will only be sequenced once
   * per `ttl` window, and it tends to be (on most platforms) multiple orders of magnitude
   * slower than `monotonic`.
   *
   * This should generally be used in situations where precise "to the millisecond" alignment to
   * the system real clock is not needed. In particular, if the system clock is updated (e.g.
   * via an NTP sync), the inner effect will not observe that update until up to `ttl`. This is
   * an acceptable tradeoff in most practical scenarios, particularly with frequent sequencing
   * of the inner effect.
   *
   * @param ttl
   *   The period of time after which the cached real time will be refreshed. Note that it will
   *   only be refreshed upon execution of the nested effect
   */
  def cachedRealTime(ttl: Duration): F[F[FiniteDuration]] = {
    implicit val self = this

    val cacheValuesF = (realTime, monotonic) mapN {
      case (realTimeNow, cacheRefreshTime) => (cacheRefreshTime, realTimeNow - cacheRefreshTime)
    }

    // Take two measurements and keep the one with the minimum offset. This will no longer be
    // required when `IO.unyielding` is merged (see #2633)
    val minCacheValuesF = (cacheValuesF, cacheValuesF) mapN {
      case (cacheValues1 @ (_, offset1), cacheValues2 @ (_, offset2)) =>
        if (offset1 < offset2) cacheValues1 else cacheValues2
    }

    minCacheValuesF.flatMap(ref).map { cacheValuesRef =>
      monotonic.flatMap { timeNow =>
        cacheValuesRef.get.flatMap {
          case (cacheRefreshTime, offset) =>
            if (timeNow >= cacheRefreshTime + ttl)
              minCacheValuesF.flatMap {
                case cacheValues @ (cacheRefreshTime, offset) =>
                  cacheValuesRef.set(cacheValues).map(_ => cacheRefreshTime + offset)
              }
            else
              pure(timeNow + offset)
        }
      }
    }
  }
}

object GenTemporal {
  def apply[F[_], E](implicit F: GenTemporal[F, E]): F.type = F
  def apply[F[_]](implicit F: GenTemporal[F, _], d: DummyImplicit): F.type = F

  implicit def genTemporalForOptionT[F[_], E](
      implicit F0: GenTemporal[F, E]): GenTemporal[OptionT[F, *], E] =
    F0 match {
      case async: Async[F @unchecked] =>
        Async.asyncForOptionT[F](async)
      case temporal =>
        instantiateGenTemporalForOptionT(temporal)
    }

  private[kernel] def instantiateGenTemporalForOptionT[F[_], E](
      F0: GenTemporal[F, E]): OptionTTemporal[F, E] =
    new OptionTTemporal[F, E] {
      override implicit protected def F: GenTemporal[F, E] = F0
    }

  implicit def genTemporalForEitherT[F[_], E0, E](
      implicit F0: GenTemporal[F, E]): GenTemporal[EitherT[F, E0, *], E] =
    F0 match {
      case async: Async[F @unchecked] =>
        Async.asyncForEitherT[F, E0](async)
      case temporal =>
        instantiateGenTemporalForEitherT(temporal)
    }

  private[kernel] def instantiateGenTemporalForEitherT[F[_], E0, E](
      F0: GenTemporal[F, E]): EitherTTemporal[F, E0, E] =
    new EitherTTemporal[F, E0, E] {
      override implicit protected def F: GenTemporal[F, E] = F0
    }

  implicit def genTemporalForKleisli[F[_], R, E](
      implicit F0: GenTemporal[F, E]): GenTemporal[Kleisli[F, R, *], E] =
    F0 match {
      case async: Async[F @unchecked] =>
        Async.asyncForKleisli[F, R](async)
      case temporal =>
        instantiateGenTemporalForKleisli(temporal)
    }

  private[kernel] def instantiateGenTemporalForKleisli[F[_], R, E](
      F0: GenTemporal[F, E]): KleisliTemporal[F, R, E] =
    new KleisliTemporal[F, R, E] {
      override implicit protected def F: GenTemporal[F, E] = F0
    }

  implicit def genTemporalForIorT[F[_], L, E](
      implicit F0: GenTemporal[F, E],
      L0: Semigroup[L]): GenTemporal[IorT[F, L, *], E] =
    F0 match {
      case async: Async[F @unchecked] =>
        Async.asyncForIorT[F, L](async, L0)
      case temporal =>
        instantiateGenTemporalForIorT(temporal)
    }

  private[kernel] def instantiateGenTemporalForIorT[F[_], L, E](F0: GenTemporal[F, E])(
      implicit L0: Semigroup[L]): IorTTemporal[F, L, E] =
    new IorTTemporal[F, L, E] {
      override implicit protected def F: GenTemporal[F, E] = F0
      override implicit protected def L: Semigroup[L] = L0
    }

  implicit def genTemporalForWriterT[F[_], L, E](
      implicit F0: GenTemporal[F, E],
      L0: Monoid[L]): GenTemporal[WriterT[F, L, *], E] =
    F0 match {
      case async: Async[F @unchecked] =>
        Async.asyncForWriterT[F, L](async, L0)
      case temporal =>
        instantiateGenTemporalForWriterT(temporal)
    }

  private[effect] def handleDuration[A](duration: Duration, ifInf: => A)(
      ifFinite: FiniteDuration => A): A =
    duration match {
      case fd: FiniteDuration => ifFinite(fd)
      case Duration.Inf => ifInf
      case d =>
        throw new IllegalArgumentException(
          s"Duration must be either a `FiniteDuration` or `Duration.Inf`, but got: $d")
    }

  private[kernel] def instantiateGenTemporalForWriterT[F[_], L, E](F0: GenTemporal[F, E])(
      implicit L0: Monoid[L]): WriterTTemporal[F, L, E] =
    new WriterTTemporal[F, L, E] {
      override implicit protected def F: GenTemporal[F, E] = F0
      override implicit protected def L: Monoid[L] = L0
    }

  private[kernel] trait OptionTTemporal[F[_], E]
      extends GenTemporal[OptionT[F, *], E]
      with GenConcurrent.OptionTGenConcurrent[F, E]
      with Clock.OptionTClock[F] {

    implicit protected def F: GenTemporal[F, E]
    protected def C: Clock[F] = F

    override protected def delegate: MonadError[OptionT[F, *], E] =
      OptionT.catsDataMonadErrorForOptionT[F, E]

    def sleep(time: FiniteDuration): OptionT[F, Unit] = OptionT.liftF(F.sleep(time))

  }

  private[kernel] trait EitherTTemporal[F[_], E0, E]
      extends GenTemporal[EitherT[F, E0, *], E]
      with GenConcurrent.EitherTGenConcurrent[F, E0, E]
      with Clock.EitherTClock[F, E0] {

    implicit protected def F: GenTemporal[F, E]
    protected def C: Clock[F] = F

    override protected def delegate: MonadError[EitherT[F, E0, *], E] =
      EitherT.catsDataMonadErrorFForEitherT[F, E, E0]

    def sleep(time: FiniteDuration): EitherT[F, E0, Unit] = EitherT.liftF(F.sleep(time))
  }

  private[kernel] trait IorTTemporal[F[_], L, E]
      extends GenTemporal[IorT[F, L, *], E]
      with GenConcurrent.IorTGenConcurrent[F, L, E]
      with Clock.IorTClock[F, L] {

    implicit protected def F: GenTemporal[F, E]
    protected def C: Clock[F] = F

    override protected def delegate: MonadError[IorT[F, L, *], E] =
      IorT.catsDataMonadErrorFForIorT[F, L, E]

    def sleep(time: FiniteDuration): IorT[F, L, Unit] = IorT.liftF(F.sleep(time))
  }

  private[kernel] trait WriterTTemporal[F[_], L, E]
      extends GenTemporal[WriterT[F, L, *], E]
      with GenConcurrent.WriterTGenConcurrent[F, L, E]
      with Clock.WriterTClock[F, L] {

    implicit protected def F: GenTemporal[F, E]
    protected def C: Clock[F] = F

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
    protected def C: Clock[F] = F

    override protected def delegate: MonadError[Kleisli[F, R, *], E] =
      Kleisli.catsDataMonadErrorForKleisli[F, R, E]

    def sleep(time: FiniteDuration): Kleisli[F, R, Unit] = Kleisli.liftF(F.sleep(time))
  }

}
