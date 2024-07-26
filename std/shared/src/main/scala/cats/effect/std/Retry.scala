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

package cats.effect.std

import cats.{~>, Monad, Semigroup, Show}
import cats.effect.kernel.GenTemporal
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.foldable._
import cats.syntax.functor._
import cats.syntax.monadError._

import scala.concurrent.duration._

/**
 * Glossary:
 *   - individual delay - the delay between retries
 *   - cumulative delay - the total delay accumulated across all retries
 */
sealed trait Retry[F[_], E] {
  import Retry.{Decision, Status}

  /**
   * The name of the policy. The name is used for informative purposes.
   */
  def name: String

  /**
   * Decides whether the action should be retried or not.
   *
   * @param status
   *   the current status
   *
   * @param error
   *   the current error
   */
  def decide(status: Status, error: E): F[Decision]

  /**
   * Applies the `transform` function to the result of the [[decide]] invocation.
   *
   * @param transform
   *   the transformation to apply
   */
  def withTransformedDecision(transform: (Status, E, Decision) => Decision): Retry[F, E]

  /**
   * The policy will retry exclusively when a caught error satisfies the `matcher`.
   *
   * @example
   *   the policy will retry on `TimeoutException` and give up :
   *   {{{
   * val timeoutExceptionOnly = Retry
   *   .exponential[IO, Throwable](1.second)
   *   .withErrorMatcher { case e: TimeoutException => IO.pure(true) }
   *
   * // will retry using exponential backoff strategy
   * Retry.retry(timeoutExceptionOnly)(IO.raiseError(new TimeoutException("oops")))
   *
   * // will never retry and give up immediately
   * Retry.retry(timeoutExceptionOnly)(IO.raiseError(new RuntimeException("oops")))
   *   }}}
   *
   * @param matcher
   *   the matcher to use
   */
  def withErrorMatcher(matcher: PartialFunction[E, F[Boolean]]): Retry[F, E]

  /**
   * Sets the name for the policy. The name is used for informative purposes.
   *
   * @param name
   *   the name to use
   */
  def withName(name: String): Retry[F, E]

  /**
   * Combines `this` policy with the `other` policy.
   *
   * The rules:
   *   - Retry will happen when '''both''' policies decides to retry
   *   - If both policies decide to retry, the '''maximum''' of the two delays will be chosen
   *
   * @example
   *
   * {{{
   * val delay1 = Retry.constantDelay[IO, Throwable](1.second)
   * val delay2 = Retry.constantDelay[IO, Throwable](2.second)
   *
   * // will always retry after 2 second
   * val policy = alwaysGiveUp.and(alwaysRetry)
   * }}}
   *
   * @example
   *
   * {{{
   * val alwaysGiveUp = Retry.alwaysGiveUp[IO, Throwable]
   * val alwaysRetry = Retry.constantDelay[IO, Throwable](1.second)
   *
   * // will never retry
   * val policy = alwaysGiveUp.and(alwaysRetry)
   * }}}
   *
   * @param other
   *   the `other` policy to combine `this` policy with
   */
  def and(other: Retry[F, E]): Retry[F, E]

  /**
   * Combines `this` policy with the `other` policy.
   *
   * The rules:
   *   - Retry will happen when either policy decides to retry
   *   - If both policies decide to retry, the '''minimum''' of the two delays will be chosen
   *
   * @example
   *
   * {{{
   * val alwaysGiveUp = Retry.alwaysGiveUp[IO, Throwable]
   * val alwaysRetry = Retry.constantDelay[IO, Throwable](1.second)
   *
   * // will always retry after 1 second
   * val policy = alwaysGiveUp.or(alwaysRetry)
   * }}}
   *
   * @param other
   *   the `other` policy to combine `this` policy with
   */
  def or(other: Retry[F, E]): Retry[F, E]

  /**
   * Combines `this` policy with the `other` policy.
   *
   * The rules:
   *   - Retry will happen when '''both''' policies decides to retry
   *   - If both policies decide to retry, the '''maximum''' of the two delays will be chosen
   *
   * @example
   *
   * {{{
   * val delay1 = Retry.constantDelay[IO, Throwable](1.second)
   * val delay2 = Retry.constantDelay[IO, Throwable](2.second)
   *
   * // will always retry after 2 second
   * val policy = alwaysGiveUp && alwaysRetry
   * }}}
   *
   * @example
   *   the policy will never retry:
   *   {{{
   * val alwaysGiveUp = Retry.alwaysGiveUp[IO, Throwable]
   * val alwaysRetry = Retry.constantDelay[IO, Throwable](1.second)
   *
   * val policy = alwaysGiveUp && alwaysRetry
   *   }}}
   *
   * @see
   *   [[and]]
   *
   * @param other
   *   the `other` policy to combine `this` policy with
   */
  final def &&(other: Retry[F, E]): Retry[F, E] = and(other)

  /**
   * Combines `this` policy with the `other` policy.
   *
   * The rules:
   *   - Retry will happen when either policy decides to retry
   *   - If both policies decide to retry, the '''minimum''' of the two delays will be chosen
   *
   * @example
   *   the policy will always retry after 1 second
   *   {{{
   * val alwaysGiveUp = Retry.alwaysGiveUp[IO, Throwable]
   * val alwaysRetry = Retry.constantDelay[IO, Throwable](1.second)
   *
   * val policy = alwaysGiveUp || alwaysRetry
   *   }}}
   *
   * @see
   *   [[or]]
   *
   * @param other
   *   the `other` policy to combine `this` policy with
   */
  final def ||(other: Retry[F, E]): Retry[F, E] = or(other)

  /**
   * Caps the '''individual''' delay between attempts by the given value.
   *
   * @example
   *   the policy will retry '''indefinitely''' with 2 seconds delay between attempts:
   *   {{{
   * val policy = Retry
   *   .constantDelay[IO, Throwable](3.second)
   *   .withCappedDelay(2.seconds)
   *   }}}
   *
   * @param cap
   *   the cap of the '''individual''' delay
   */
  final def withCappedDelay(cap: FiniteDuration): Retry[F, E] =
    withTransformedDecision {
      case (_, _, decision: Decision.Retry) =>
        Decision.retry(decision.delay.min(cap))

      case (_, _, _: Decision.GiveUp) =>
        Decision.giveUp
    }.withName(s"CapDelay($name, cap=$cap)")

  /**
   * Limits the number of maximum retries.
   *
   * @example
   *   the policy will retry 5 times at most:
   *   {{{
   * val policy = Retry
   *   .constantDelay[IO, Throwable](1.second)
   *   .withMaxRetries(5)
   *   }}}
   *
   * @param max
   *   the maximum number of retries
   */
  final def withMaxRetries(max: Int): Retry[F, E] =
    withTransformedDecision {
      case (status, _, decision: Decision.Retry) =>
        if (status.retriesTotal >= max) Decision.giveUp else decision

      case (_, _, _: Decision.GiveUp) =>
        Decision.giveUp
    }.withName(s"MaxRetries($name, max=$max)")

  /**
   * Gives up once the '''individual''' delay is greater than the given `threshold`.
   *
   * @example
   *   the policy will give up once the individual delay between attempts will be more than 5
   *   seconds:
   *   {{{
   * val policy = Retry
   *   .exponential[IO, Throwable](1.second)
   *   .withMaxDelay(5.seconds)
   *   }}}
   *
   * @example
   *   the policy will retry '''indefinitely''' because individual delay is never greater than
   *   the threshold:
   *   {{{
   * val policy = Retry
   *   .constantDelay(1.second)
   *   .withMaxDelay(2.seconds)
   *   }}}
   *
   * @param threshold
   *   the maximum '''individual''' delay
   */
  final def withMaxDelay(threshold: FiniteDuration): Retry[F, E] =
    withTransformedDecision {
      case (_, _, decision: Decision.Retry) =>
        if (decision.delay >= threshold) Decision.giveUp else decision

      case (_, _, _: Decision.GiveUp) =>
        Decision.giveUp
    }.withName(s"MaxDelay($name, threshold=$threshold)")

  /**
   * Gives up once the '''cumulative''' delay is greater than the given `threshold`.
   *
   * @example
   *   the policy will give up once the cumulative delay between attempts will be more than 5
   *   seconds:
   *   {{{
   * val policy = Retry
   *   .exponential[IO, Throwable](1.second)
   *   .withCumulativeDelay(5.seconds)
   *   }}}
   *
   * @param threshold
   *   the maximum '''cumulative''' delay
   */
  final def withMaxCumulativeDelay(threshold: FiniteDuration): Retry[F, E] =
    withTransformedDecision {
      case (status, _, decision: Decision.Retry) =>
        if (status.cumulativeDelay + decision.delay >= threshold) Decision.giveUp
        else decision

      case (_, _, _: Decision.GiveUp) =>
        Decision.giveUp
    }.withName(s"MaxCumulativeDelay($name, threshold=$threshold)")

  final def mapK[G[_]: Monad](f: F ~> G): Retry[G, E] =
    Retry.named(s"MapK($name)")((s, e) => f(decide(s, e)))

  override def toString: String =
    name
}

object Retry {

  /* Represents the status of a retry operation. */
  sealed trait Status {
    /* The total number of retries that have occurred. */
    def retriesTotal: Int

    /* The total delay accumulated across all retries. */
    def cumulativeDelay: FiniteDuration

    def withRetry(delay: FiniteDuration): Status
  }

  object Status {
    private[std] val initial: Status = Impl(0, Duration.Zero)

    private[std] def apply(
        retriesTotal: Int,
        cumulativeDelay: FiniteDuration
    ): Status =
      Impl(retriesTotal, cumulativeDelay)

    private final case class Impl(
        retriesTotal: Int,
        cumulativeDelay: FiniteDuration
    ) extends Status {
      def withRetry(delay: FiniteDuration): Status =
        copy(
          retriesTotal = retriesTotal + 1,
          cumulativeDelay = cumulativeDelay + delay
        )
    }
  }

  /* Represents the retry decision. */
  sealed trait Decision extends Product with Serializable
  object Decision {

    /* No more retries. */
    sealed trait GiveUp extends Decision

    /* The operation must be retried after the delay. */
    sealed trait Retry extends Decision {
      /* How long to wait before the next attempt. */
      def delay: FiniteDuration
    }

    def giveUp: Decision = GiveUp

    def retry(delay: FiniteDuration): Decision = RetryImpl(delay)

    private case object GiveUp extends GiveUp
    private final case class RetryImpl(delay: FiniteDuration) extends Retry
  }

  sealed trait BackoffMultiplier extends Product with Serializable
  object BackoffMultiplier {

    def const(multiplier: Double): BackoffMultiplier =
      Const(multiplier)

    def randomized(min: Double, max: Double): BackoffMultiplier =
      Randomized(min, max)

    private[Retry] final case class Const(multiplier: Double) extends BackoffMultiplier
    private[Retry] final case class Randomized(
        min: Double,
        max: Double
    ) extends BackoffMultiplier
  }

  /**
   * Evaluates `fa` with the given retry `policy`.
   *
   * @param policy
   *   the policy to use
   *
   * @param fa
   *   the effect
   */
  def retry[F[_], A, E](policy: Retry[F, E])(fa: F[A])(implicit F: GenTemporal[F, E]): F[A] =
    doRetry(policy, None)(fa)

  /**
   * Evaluates `fa` with the given retry `policy`.
   *
   * @param policy
   *   the policy to use
   *
   * @param onRetry
   *   the effect to invoke on every retry decision
   *
   * @param fa
   *   the effect
   */
  def retry[F[_], A, E](
      policy: Retry[F, E],
      onRetry: (Status, E, Decision) => F[Unit]
  )(fa: F[A])(implicit F: GenTemporal[F, E]): F[A] =
    doRetry(policy, Some(onRetry))(fa)

  /**
   * The return policy that always gives up.
   */
  def alwaysGiveUp[F[_]: Monad, E]: Retry[F, E] =
    const("AlwaysGiveUp", Decision.giveUp)

  /**
   * The policy that uses the given `delay` between attempts.
   *
   * @param delay
   *   the delay to use between retries
   */
  def constantDelay[F[_]: Monad, E](delay: FiniteDuration): Retry[F, E] =
    const(s"ConstantDelay($delay)", Decision.retry(delay))

  /**
   * The policy with a fixed number of retries.
   *
   * @example
   *   {{{
   * val policy = Retry.constantDelay[IO, Throwable](1.second) && Retry.maxRetries[IO, Throwable](10)
   *   }}}
   *
   * @param max
   *   the maximum number of retries
   */
  def maxRetries[F[_]: Monad, E](max: Int): Retry[F, E] =
    named(s"MaxRetries($max)") { (status, _) =>
      Monad[F].pure(
        if (status.retriesTotal >= max) Decision.giveUp else Decision.retry(Duration.Zero)
      )
    }

  /**
   * The policy that uses exponential backoff strategy with the given `baseDelay`. The backoff
   * multiplier is `2.0` and randomization factor `0.5`. The delays are always jittered.
   *
   * The simplified formula is:
   * {{{
   *   retry_delay = randomization_factor * random_double[0:1] * base_delay * (backoff_multiplier ^ retry_attempt)
   * }}}
   *
   * @example
   *   an exponential backoff with maximum of 10 attempts:
   *   {{{
   * val policy = Retry
   *   .exponentialBackoff[IO, Throwable](1.second)
   *   .withMaxRetries(10)
   *   }}}
   *
   * @see
   *   [[https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/]]
   *
   * @param baseDelay
   *   the base delay to use
   */
  def exponentialBackoff[F[_]: Monad: Random, E](baseDelay: FiniteDuration): Retry[F, E] =
    exponentialBackoff(baseDelay, Some(BackoffMultiplier.const(2.0)), Some(0.5))

  /**
   * The policy that uses exponential backoff strategy with the given configuration. The delays
   * are always jittered.
   *
   * The simplified formula is:
   * {{{
   *   retry_delay = randomization_factor * random_double[0:1] * base_delay * (backoff_multiplier ^ retry_attempt)
   * }}}
   *
   * @example
   *   an exponential backoff with maximum of 10 attempts:
   *   {{{
   * val policy = Retry
   *   .exponentialBackoff[IO, Throwable](1.second, Some(Retry.BackoffMultiplier.const(1.5)), Some(0.75))
   *   .withMaxRetries(10)
   *   }}}
   *
   * @see
   *   [[https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/]]
   *
   * @param baseDelay
   *   the base delay to use
   *
   * @param backoffMultiplier
   *   the backoff multiplier to use. A random double between `0.0` and `1.0` will be used if
   *   undefined
   *
   * @param randomizationFactor
   *   the randomization factor to use. The `1.0` will be used if undefined
   */
  def exponentialBackoff[F[_]: Monad: Random, E](
      baseDelay: FiniteDuration,
      backoffMultiplier: Option[BackoffMultiplier],
      randomizationFactor: Option[Double]
  ): Retry[F, E] = {
    val name = (backoffMultiplier, randomizationFactor) match {
      case (Some(multiplier), Some(factor)) =>
        s"Backoff(baseDelay=$baseDelay, multiplier=$multiplier, randomizationFactor=$factor)"

      case (Some(multiplier), None) =>
        s"Backoff(baseDelay=$baseDelay, multiplier=$multiplier)"

      case (None, Some(factor)) =>
        s"Backoff(baseDelay=$baseDelay, randomizationFactor=$factor)"

      case (None, None) =>
        s"Backoff(baseDelay=$baseDelay)"
    }

    named[F, E](name) { (status, _) =>
      val multiplier = backoffMultiplier match {
        case Some(BackoffMultiplier.Const(multiplier)) => Monad[F].pure(multiplier)
        case Some(BackoffMultiplier.Randomized(min, max)) => Random[F].betweenDouble(min, max)
        case None => Random[F].nextDouble
      }

      (multiplier, Random[F].nextDouble).mapN { (multiplier, random) =>
        val factor = randomizationFactor.getOrElse(1.0)
        val e = math.pow(multiplier, status.retriesTotal.toDouble).toLong
        val backoff = safeMultiply(baseDelay, e)
        val delay = factor * random * backoff.toNanos

        Decision.retry(delay.toLong.nanos)
      }
    }
  }

  /**
   * Creates a policy that uses the given decider function.
   *
   * @param decider
   *   the function to decide whether to continue
   */
  def apply[F[_]: Monad, E](decider: (Status, E) => F[Decision]): Retry[F, E] =
    named("f()")(decider)

  /**
   * Creates a policy with the given name and decider function.
   *
   * @param name
   *   the name of the policy. The name is used for informative purposes
   *
   * @param decider
   *   the function to decide whether to continue
   */
  def named[F[_]: Monad, E](name: String)(decider: (Status, E) => F[Decision]): Retry[F, E] =
    RetryImpl(name, decider)

  implicit def retrySemigroup[F[_], E]: Semigroup[Retry[F, E]] =
    (x, y) => x && y

  implicit def retryShow[F[_], E]: Show[Retry[F, E]] =
    retry => retry.name

  private def doRetry[F[_], A, E](
      retry: Retry[F, E],
      onRetry: Option[(Status, E, Decision) => F[Unit]]
  )(fa: F[A])(implicit F: GenTemporal[F, E]): F[A] = {
    def onError(status: Status, error: E): F[Either[Status, A]] =
      retry
        .decide(status, error)
        .flatTap(decision => onRetry.traverse_(_(status, error, decision)))
        .flatMap {
          case retry: Decision.Retry =>
            F.delayBy(F.pure(Left(status.withRetry(retry.delay))), retry.delay)
          case _: Decision.GiveUp =>
            F.raiseError(error)
        }

    F.tailRecM(Status.initial) { status =>
      fa.redeemWith(error => onError(status, error), success => F.pure(Right(success)))
    }
  }

  private def const[F[_]: Monad, E](name: String, decision: Decision): Retry[F, E] = {
    val d = Monad[F].pure(decision)
    named(name)((_, _) => d)
  }

  private final case class RetryImpl[F[_]: Monad, E](
      name: String,
      decider: (Status, E) => F[Decision]
  ) extends Retry[F, E] {

    def decide(status: Status, error: E): F[Decision] =
      decider(status, error)

    def and(other: Retry[F, E]): Retry[F, E] =
      Retry.named(s"($name && ${other.name})") { (status, error) =>
        (decide(status, error), other.decide(status, error)).mapN {
          case (a: Decision.Retry, b: Decision.Retry) => Decision.retry(a.delay.max(b.delay))
          case (_, _) => Decision.giveUp
        }
      }

    def or(other: Retry[F, E]): Retry[F, E] =
      Retry.named(s"($name || ${other.name})") { (status, error) =>
        (decide(status, error), other.decide(status, error)).mapN {
          case (a: Decision.Retry, b: Decision.Retry) => Decision.retry(a.delay.min(b.delay))
          case (_: Decision.GiveUp, other: Decision.Retry) => other
          case (that: Decision.Retry, _: Decision.GiveUp) => that
          case (_: Decision.GiveUp, _: Decision.GiveUp) => Decision.giveUp
        }
      }

    def withErrorMatcher(matcher: PartialFunction[E, F[Boolean]]): Retry[F, E] =
      copy(decider = { (status, error) =>
        matcher
          .applyOrElse(error, (_: E) => Monad[F].pure(false))
          .ifM(decider(status, error), Monad[F].pure(Decision.giveUp))
      })

    def withName(name: String): Retry[F, E] =
      copy(name = name)

    def withTransformedDecision(transform: (Status, E, Decision) => Decision): Retry[F, E] =
      copy(decider = { (status, error) =>
        decide(status, error).map(decision => transform(status, error, decision))
      })
  }

  private val LongMax: BigInt = BigInt(Long.MaxValue)
  private def safeMultiply(duration: FiniteDuration, multiplier: Long): FiniteDuration = {
    val durationNanos = BigInt(duration.toNanos)
    val resultNanos = durationNanos * BigInt(multiplier)
    val safeResultNanos = resultNanos.min(LongMax)
    safeResultNanos.toLong.nanos
  }

}
