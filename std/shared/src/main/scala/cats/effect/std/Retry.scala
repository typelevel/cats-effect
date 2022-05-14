package cats.effect.std
package retry

import cats._
import cats.syntax.all._
import cats.arrow.FunctionK
import cats.kernel.BoundedSemilattice
import cats.effect.kernel.Temporal
import retry.PolicyDecision._
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Random
import java.util.concurrent.TimeUnit

abstract class Retry[F[_]] {
  def nextRetry(status: Retry.Status): F[PolicyDecision]

  def followedBy(r: Retry[F]): Retry[F]

  /**
   * Combine this schedule with another schedule, giving up when either of the schedules want to
   * give up and choosing the maximum of the two delays when both of the schedules want to delay
   * the next retry. The dual of the `meet` operation.
   */
  def join(r: Retry[F]): Retry[F]

  /**
   * Combine this schedule with another schedule, giving up when both of the schedules want to
   * give up and choosing the minimum of the two delays when both of the schedules want to delay
   * the next retry. The dual of the `join` operation.
   */
  def meet(r: Retry[F]): Retry[F]

  def mapDelay(f: FiniteDuration => FiniteDuration): Retry[F]

  def flatMapDelay(f: FiniteDuration => F[FiniteDuration]): Retry[F]

  def mapK[G[_]: Monad](f: F ~> G): Retry[G]
}
object Retry {
  final case class Status(
      retriesSoFar: Int,
      cumulativeDelay: FiniteDuration,
      previousDelay: Option[FiniteDuration]
  ) {
    def addRetry(delay: FiniteDuration): Retry.Status = Retry.Status(
      retriesSoFar = this.retriesSoFar + 1,
      cumulativeDelay = this.cumulativeDelay + delay,
      previousDelay = Some(delay)
    )
  }

  val NoRetriesYet = Retry.Status(0, Duration.Zero, None)

  def apply[F[_]: Monad](
    nextRetry: Retry.Status => F[PolicyDecision],
    pretty: String = "<retry>"
  ): Retry[F] =
    new RetryImpl[F](nextRetry, pretty)

  def lift[F[_]: Monad](
    nextRetry: Retry.Status => PolicyDecision,
    pretty: String = "<retry>"
  ): Retry[F] =
    apply[F](
      status => nextRetry(status).pure[F],
      pretty
    )

// implicit def boundedSemilatticeForRetry[F[_]](
//     implicit F: Applicative[F]): BoundedSemilattice[Retry[F]] =
//   new BoundedSemilattice[Retry[F]] {
//     override def empty: Retry[F] =
//       RetryPolicies.constantDelay[F](Duration.Zero)

//     override def combine(
//         x: Retry[F],
//         y: Retry[F]
//     ): Retry[F] = x.join(y)
//   }

// implicit def showForRetry[F[_]]: Show[Retry[F]] =
//   Show.show(_.show)

  private final case class RetryImpl[F[_]: Monad](nextRetry_ : Retry.Status => F[PolicyDecision], pretty: String) extends Retry[F] {

    override def nextRetry(status: Retry.Status): F[PolicyDecision] =
      nextRetry_(status)

    override def toString: String = pretty

    def followedBy(r: Retry[F]): Retry[F] =
      Retry(
        status => (nextRetry(status), r.nextRetry(status)).mapN {
          case (GiveUp, pd) => pd
          case (pd, _) => pd
        },
        s"$this.followedBy($r)"
      )

    def join(r: Retry[F]): Retry[F] =
      Retry[F](
        status => (nextRetry(status), r.nextRetry(status)).mapN {
          case (DelayAndRetry(a), DelayAndRetry(b)) => DelayAndRetry(a max b)
          case _ => GiveUp
        },
        s"$this.join($r)"
      )

    def meet(r: Retry[F]): Retry[F] =
      Retry[F](
        status => (nextRetry(status), r.nextRetry(status)).mapN {
          case (DelayAndRetry(a), DelayAndRetry(b)) => DelayAndRetry(a min b)
          case (s @ DelayAndRetry(_), GiveUp) => s
          case (GiveUp, s @ DelayAndRetry(_)) => s
          case _ => GiveUp
        },
        s"$this.meet($r)"
      )

    def mapDelay(f: FiniteDuration => FiniteDuration): Retry[F] =
      Retry(
        status => nextRetry(status).map {
          case GiveUp => GiveUp
          case DelayAndRetry(d) => DelayAndRetry(f(d))
        },
        s"$this.mapDelay(<function>)"
    )

    def flatMapDelay(f: FiniteDuration => F[FiniteDuration]): Retry[F] =
      Retry(
        status => nextRetry(status).flatMap {
          case GiveUp => GiveUp.pure[F].widen[PolicyDecision]
          case DelayAndRetry(d) => f(d).map(DelayAndRetry(_))
        },
        s"$this.flatMapDelay(<function>)"
      )

    def mapK[G[_]: Monad](f: F ~> G): Retry[G] =
      Retry(
        status => f(nextRetry(status)),
        s"$this.mapK(<FunctionK>)"
      )
  }
}

/////// 

final case class RetryStatus(
    retriesSoFar: Int,
    cumulativeDelay: FiniteDuration,
    previousDelay: Option[FiniteDuration]
) {
  def addRetry(delay: FiniteDuration): RetryStatus = RetryStatus(
    retriesSoFar = this.retriesSoFar + 1,
    cumulativeDelay = this.cumulativeDelay + delay,
    previousDelay = Some(delay)
  )
}

object RetryStatus {
  val NoRetriesYet = RetryStatus(0, Duration.Zero, None)
}

sealed trait PolicyDecision

object PolicyDecision {
  case object GiveUp extends PolicyDecision

  final case class DelayAndRetry(
      delay: FiniteDuration
  ) extends PolicyDecision
}

case class RetryPolicy[M[_]](
    decideNextRetry: RetryStatus => M[PolicyDecision]
) {
  def show: String = toString

  def followedBy(rp: RetryPolicy[M])(implicit M: Apply[M]): RetryPolicy[M] =
    RetryPolicy.withShow(
      status =>
        M.map2(decideNextRetry(status), rp.decideNextRetry(status)) {
          case (GiveUp, pd) => pd
          case (pd, _) => pd
        },
      show"$show.followedBy($rp)"
    )

  /**
   * Combine this schedule with another schedule, giving up when either of the schedules want to
   * give up and choosing the maximum of the two delays when both of the schedules want to delay
   * the next retry. The dual of the `meet` operation.
   */
  def join(rp: RetryPolicy[M])(implicit M: Apply[M]): RetryPolicy[M] =
    RetryPolicy.withShow[M](
      status =>
        M.map2(decideNextRetry(status), rp.decideNextRetry(status)) {
          case (DelayAndRetry(a), DelayAndRetry(b)) => DelayAndRetry(a max b)
          case _ => GiveUp
        },
      show"$show.join($rp)"
    )

  /**
   * Combine this schedule with another schedule, giving up when both of the schedules want to
   * give up and choosing the minimum of the two delays when both of the schedules want to delay
   * the next retry. The dual of the `join` operation.
   */
  def meet(rp: RetryPolicy[M])(implicit M: Apply[M]): RetryPolicy[M] =
    RetryPolicy.withShow[M](
      status =>
        M.map2(decideNextRetry(status), rp.decideNextRetry(status)) {
          case (DelayAndRetry(a), DelayAndRetry(b)) => DelayAndRetry(a min b)
          case (s @ DelayAndRetry(_), GiveUp) => s
          case (GiveUp, s @ DelayAndRetry(_)) => s
          case _ => GiveUp
        },
      show"$show.meet($rp)"
    )

  def mapDelay(
      f: FiniteDuration => FiniteDuration
  )(implicit M: Functor[M]): RetryPolicy[M] =
    RetryPolicy.withShow(
      status =>
        M.map(decideNextRetry(status)) {
          case GiveUp => GiveUp
          case DelayAndRetry(d) => DelayAndRetry(f(d))
        },
      show"$show.mapDelay(<function>)"
    )

  def flatMapDelay(
      f: FiniteDuration => M[FiniteDuration]
  )(implicit M: Monad[M]): RetryPolicy[M] =
    RetryPolicy.withShow(
      status =>
        M.flatMap(decideNextRetry(status)) {
          case GiveUp => M.pure(GiveUp)
          case DelayAndRetry(d) => M.map(f(d))(DelayAndRetry(_))
        },
      show"$show.flatMapDelay(<function>)"
    )

  def mapK[N[_]](nt: FunctionK[M, N]): RetryPolicy[N] =
    RetryPolicy.withShow(
      status => nt(decideNextRetry(status)),
      show"$show.mapK(<FunctionK>)"
    )
}

object RetryPolicy {
  def lift[M[_]](
      f: RetryStatus => PolicyDecision
  )(implicit M: Applicative[M]): RetryPolicy[M] =
    RetryPolicy[M](decideNextRetry = retryStatus => M.pure(f(retryStatus)))

  def withShow[M[_]](
      decideNextRetry: RetryStatus => M[PolicyDecision],
      pretty: => String
  ): RetryPolicy[M] =
    new RetryPolicy[M](decideNextRetry) {
      override def show: String = pretty
      override def toString: String = pretty
    }

  def liftWithShow[M[_]: Applicative](
      decideNextRetry: RetryStatus => PolicyDecision,
      pretty: => String
  ): RetryPolicy[M] =
    withShow(rs => Applicative[M].pure(decideNextRetry(rs)), pretty)

  implicit def boundedSemilatticeForRetryPolicy[M[_]](
      implicit M: Applicative[M]): BoundedSemilattice[RetryPolicy[M]] =
    new BoundedSemilattice[RetryPolicy[M]] {
      override def empty: RetryPolicy[M] =
        RetryPolicies.constantDelay[M](Duration.Zero)

      override def combine(
          x: RetryPolicy[M],
          y: RetryPolicy[M]
      ): RetryPolicy[M] = x.join(y)
    }

  implicit def showForRetryPolicy[M[_]]: Show[RetryPolicy[M]] =
    Show.show(_.show)
}

object RetryPolicies {
  private val LongMax: BigInt = BigInt(Long.MaxValue)

  /*
   * Multiply the given duration by the given multiplier, but cap the result to
   * ensure we don't try to create a FiniteDuration longer than 2^63 - 1 nanoseconds.
   *
   * Note: despite the "safe" in the name, we can still create an invalid
   * FiniteDuration if the multiplier is negative. But an assumption of the library
   * as a whole is that nobody would be silly enough to use negative delays.
   */
  private def safeMultiply(
      duration: FiniteDuration,
      multiplier: Long
  ): FiniteDuration = {
    val durationNanos = BigInt(duration.toNanos)
    val resultNanos = durationNanos * BigInt(multiplier)
    val safeResultNanos = resultNanos min LongMax
    FiniteDuration(safeResultNanos.toLong, TimeUnit.NANOSECONDS)
  }

  /**
   * Don't retry at all and always give up. Only really useful for combining with other
   * policies.
   */
  def alwaysGiveUp[M[_]: Applicative]: RetryPolicy[M] =
    RetryPolicy.liftWithShow(Function.const(GiveUp), "alwaysGiveUp")

  /**
   * Delay by a constant amount before each retry. Never give up.
   */
  def constantDelay[M[_]: Applicative](delay: FiniteDuration): RetryPolicy[M] =
    RetryPolicy.liftWithShow(
      Function.const(DelayAndRetry(delay)),
      show"constantDelay($delay)"
    )

  /**
   * Each delay is twice as long as the previous one. Never give up.
   */
  def exponentialBackoff[M[_]: Applicative](
      baseDelay: FiniteDuration
  ): RetryPolicy[M] =
    RetryPolicy.liftWithShow(
      { status =>
        val delay =
          safeMultiply(baseDelay, Math.pow(2, status.retriesSoFar).toLong)
        DelayAndRetry(delay)
      },
      show"exponentialBackOff(baseDelay=$baseDelay)"
    )

  /**
   * Retry without delay, giving up after the given number of retries.
   */
  def limitRetries[M[_]: Applicative](maxRetries: Int): RetryPolicy[M] =
    RetryPolicy.liftWithShow(
      { status =>
        if (status.retriesSoFar >= maxRetries) {
          GiveUp
        } else {
          DelayAndRetry(Duration.Zero)
        }
      },
      show"limitRetries(maxRetries=$maxRetries)"
    )

  /**
   * Delay(n) = Delay(n - 2) + Delay(n - 1)
   *
   * e.g. if `baseDelay` is 10 milliseconds, the delays before each retry will be 10 ms, 10 ms,
   * 20 ms, 30ms, 50ms, 80ms, 130ms, ...
   */
  def fibonacciBackoff[M[_]: Applicative](
      baseDelay: FiniteDuration
  ): RetryPolicy[M] =
    RetryPolicy.liftWithShow(
      { status =>
        val delay =
          safeMultiply(baseDelay, Fibonacci.fibonacci(status.retriesSoFar + 1))
        DelayAndRetry(delay)
      },
      show"fibonacciBackoff(baseDelay=$baseDelay)"
    )

  /**
   * "Full jitter" backoff algorithm. See
   * https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/
   */
  def fullJitter[M[_]: Applicative](baseDelay: FiniteDuration): RetryPolicy[M] =
    RetryPolicy.liftWithShow(
      { status =>
        val e = Math.pow(2, status.retriesSoFar).toLong
        val maxDelay = safeMultiply(baseDelay, e)
        val delayNanos = (maxDelay.toNanos * Random.nextDouble()).toLong
        DelayAndRetry(new FiniteDuration(delayNanos, TimeUnit.NANOSECONDS))
      },
      show"fullJitter(baseDelay=$baseDelay)"
    )

  /**
   * Set an upper bound on any individual delay produced by the given policy.
   */
  def capDelay[M[_]: Applicative](
      cap: FiniteDuration,
      policy: RetryPolicy[M]
  ): RetryPolicy[M] =
    policy.meet(constantDelay(cap))

  /**
   * Add an upper bound to a policy such that once the given time-delay amount <b>per try</b>
   * has been reached or exceeded, the policy will stop retrying and give up. If you need to
   * stop retrying once <b>cumulative</b> delay reaches a time-delay amount, use
   * [[limitRetriesByCumulativeDelay]].
   */
  def limitRetriesByDelay[M[_]: Applicative](
      threshold: FiniteDuration,
      policy: RetryPolicy[M]
  ): RetryPolicy[M] = {
    def decideNextRetry(status: RetryStatus): M[PolicyDecision] =
      policy.decideNextRetry(status).map {
        case r @ DelayAndRetry(delay) =>
          if (delay > threshold) GiveUp else r
        case GiveUp => GiveUp
      }

    RetryPolicy.withShow[M](
      decideNextRetry,
      show"limitRetriesByDelay(threshold=$threshold, $policy)"
    )
  }

  /**
   * Add an upperbound to a policy such that once the cumulative delay over all retries has
   * reached or exceeded the given limit, the policy will stop retrying and give up.
   */
  def limitRetriesByCumulativeDelay[M[_]: Applicative](
      threshold: FiniteDuration,
      policy: RetryPolicy[M]
  ): RetryPolicy[M] = {
    def decideNextRetry(status: RetryStatus): M[PolicyDecision] =
      policy.decideNextRetry(status).map {
        case r @ DelayAndRetry(delay) =>
          if (status.cumulativeDelay + delay >= threshold) GiveUp else r
        case GiveUp => GiveUp
      }

    RetryPolicy.withShow[M](
      decideNextRetry,
      show"limitRetriesByCumulativeDelay(threshold=$threshold, $policy)"
    )
  }
}

trait Sleep[M[_]] {
  def sleep(delay: FiniteDuration): M[Unit]
}

object Sleep {
  def apply[M[_]](implicit sleep: Sleep[M]): Sleep[M] = sleep

  implicit def sleepUsingTemporal[F[_]](implicit t: Temporal[F]): Sleep[F] =
    (delay: FiniteDuration) => t.sleep(delay)
}

object implicits extends syntax.AllSyntax

sealed trait RetryDetails {
  def retriesSoFar: Int
  def cumulativeDelay: FiniteDuration
  def givingUp: Boolean
  def upcomingDelay: Option[FiniteDuration]
}

object RetryDetails {
  final case class GivingUp(
      totalRetries: Int,
      totalDelay: FiniteDuration
  ) extends RetryDetails {
    val retriesSoFar: Int = totalRetries
    val cumulativeDelay: FiniteDuration = totalDelay
    val givingUp: Boolean = true
    val upcomingDelay: Option[FiniteDuration] = None
  }

  final case class WillDelayAndRetry(
      nextDelay: FiniteDuration,
      retriesSoFar: Int,
      cumulativeDelay: FiniteDuration
  ) extends RetryDetails {
    val givingUp: Boolean = false
    val upcomingDelay: Option[FiniteDuration] = Some(nextDelay)
  }
}

package object retry_ {
  @deprecated("Use retryingOnFailures instead", "2.1.0")
  def retryingM[A] = new RetryingOnFailuresPartiallyApplied[A]
  def retryingOnFailures[A] = new RetryingOnFailuresPartiallyApplied[A]

  private def retryingOnFailuresImpl[M[_], A](
      policy: RetryPolicy[M],
      wasSuccessful: A => M[Boolean],
      onFailure: (A, RetryDetails) => M[Unit],
      status: RetryStatus,
      a: A
  )(implicit M: Monad[M], S: Sleep[M]): M[Either[RetryStatus, A]] = {

    def onFalse: M[Either[RetryStatus, A]] = for {
      nextStep <- applyPolicy(policy, status)
      _ <- onFailure(a, buildRetryDetails(status, nextStep))
      result <- nextStep match {
        case NextStep.RetryAfterDelay(delay, updatedStatus) =>
          S.sleep(delay) *>
            M.pure(Left(updatedStatus)) // continue recursion
        case NextStep.GiveUp =>
          M.pure(Right(a)) // stop the recursion
      }
    } yield result

    wasSuccessful(a).ifM(
      M.pure(Right(a)), // stop the recursion
      onFalse
    )
  }

  private[retry] class RetryingOnFailuresPartiallyApplied[A] {
    def apply[M[_]](
        policy: RetryPolicy[M],
        wasSuccessful: A => M[Boolean],
        onFailure: (A, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(implicit M: Monad[M], S: Sleep[M]): M[A] = M.tailRecM(RetryStatus.NoRetriesYet) {
      status =>
        action.flatMap { a =>
          retryingOnFailuresImpl(policy, wasSuccessful, onFailure, status, a)
        }
    }
  }

  def retryingOnSomeErrors[A] = new RetryingOnSomeErrorsPartiallyApplied[A]

  private def retryingOnSomeErrorsImpl[M[_], A, E](
      policy: RetryPolicy[M],
      isWorthRetrying: E => M[Boolean],
      onError: (E, RetryDetails) => M[Unit],
      status: RetryStatus,
      attempt: Either[E, A]
  )(implicit ME: MonadError[M, E], S: Sleep[M]): M[Either[RetryStatus, A]] = attempt match {
    case Left(error) =>
      isWorthRetrying(error).ifM(
        for {
          nextStep <- applyPolicy(policy, status)
          _ <- onError(error, buildRetryDetails(status, nextStep))
          result <- nextStep match {
            case NextStep.RetryAfterDelay(delay, updatedStatus) =>
              S.sleep(delay) *>
                ME.pure(Left(updatedStatus)) // continue recursion
            case NextStep.GiveUp =>
              ME.raiseError[A](error).map(Right(_)) // stop the recursion
          }
        } yield result,
        ME.raiseError[A](error).map(Right(_)) // stop the recursion
      )
    case Right(success) =>
      ME.pure(Right(success)) // stop the recursion
  }

  private[retry] class RetryingOnSomeErrorsPartiallyApplied[A] {
    def apply[M[_], E](
        policy: RetryPolicy[M],
        isWorthRetrying: E => M[Boolean],
        onError: (E, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(implicit ME: MonadError[M, E], S: Sleep[M]): M[A] =
      ME.tailRecM(RetryStatus.NoRetriesYet) { status =>
        ME.attempt(action).flatMap { attempt =>
          retryingOnSomeErrorsImpl(
            policy,
            isWorthRetrying,
            onError,
            status,
            attempt
          )
        }
      }
  }

  def retryingOnAllErrors[A] = new RetryingOnAllErrorsPartiallyApplied[A]

  private[retry] class RetryingOnAllErrorsPartiallyApplied[A] {
    def apply[M[_], E](
        policy: RetryPolicy[M],
        onError: (E, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(implicit ME: MonadError[M, E], S: Sleep[M]): M[A] =
      retryingOnSomeErrors[A].apply[M, E](policy, _ => ME.pure(true), onError)(
        action
      )
  }

  def retryingOnFailuresAndSomeErrors[A] =
    new RetryingOnFailuresAndSomeErrorsPartiallyApplied[A]

  private[retry] class RetryingOnFailuresAndSomeErrorsPartiallyApplied[A] {
    def apply[M[_], E](
        policy: RetryPolicy[M],
        wasSuccessful: A => M[Boolean],
        isWorthRetrying: E => M[Boolean],
        onFailure: (A, RetryDetails) => M[Unit],
        onError: (E, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(implicit ME: MonadError[M, E], S: Sleep[M]): M[A] = {

      ME.tailRecM(RetryStatus.NoRetriesYet) { status =>
        ME.attempt(action).flatMap {
          case Right(a) =>
            retryingOnFailuresImpl(policy, wasSuccessful, onFailure, status, a)
          case attempt =>
            retryingOnSomeErrorsImpl(
              policy,
              isWorthRetrying,
              onError,
              status,
              attempt
            )
        }
      }
    }
  }

  def retryingOnFailuresAndAllErrors[A] =
    new RetryingOnFailuresAndAllErrorsPartiallyApplied[A]

  private[retry] class RetryingOnFailuresAndAllErrorsPartiallyApplied[A] {
    def apply[M[_], E](
        policy: RetryPolicy[M],
        wasSuccessful: A => M[Boolean],
        onFailure: (A, RetryDetails) => M[Unit],
        onError: (E, RetryDetails) => M[Unit]
    )(
        action: => M[A]
    )(implicit ME: MonadError[M, E], S: Sleep[M]): M[A] =
      retryingOnFailuresAndSomeErrors[A].apply[M, E](
        policy,
        wasSuccessful,
        _ => ME.pure(true),
        onFailure,
        onError
      )(
        action
      )
  }

  def noop[M[_]: Monad, A]: (A, RetryDetails) => M[Unit] =
    (_, _) => Monad[M].pure(())

  private[retry] def applyPolicy[M[_]: Monad](
      policy: RetryPolicy[M],
      retryStatus: RetryStatus
  ): M[NextStep] =
    policy.decideNextRetry(retryStatus).map {
      case PolicyDecision.DelayAndRetry(delay) =>
        NextStep.RetryAfterDelay(delay, retryStatus.addRetry(delay))
      case PolicyDecision.GiveUp =>
        NextStep.GiveUp
    }

  private[retry] def buildRetryDetails(
      currentStatus: RetryStatus,
      nextStep: NextStep
  ): RetryDetails =
    nextStep match {
      case NextStep.RetryAfterDelay(delay, _) =>
        RetryDetails.WillDelayAndRetry(
          delay,
          currentStatus.retriesSoFar,
          currentStatus.cumulativeDelay
        )
      case NextStep.GiveUp =>
        RetryDetails.GivingUp(
          currentStatus.retriesSoFar,
          currentStatus.cumulativeDelay
        )
    }

  private[retry] sealed trait NextStep

  private[retry] object NextStep {
    case object GiveUp extends NextStep

    final case class RetryAfterDelay(
        delay: FiniteDuration,
        updatedStatus: RetryStatus
    ) extends NextStep
  }
}

trait AllSyntax extends RetrySyntax

trait RetrySyntax {
  implicit final def retrySyntaxBase[M[_], A](
      action: => M[A]
  ): RetryingOps[M, A] =
    new RetryingOps[M, A](action)

  implicit final def retrySyntaxError[M[_], A, E](
      action: => M[A]
  )(implicit M: MonadError[M, E]): RetryingErrorOps[M, A, E] =
    new RetryingErrorOps[M, A, E](action)
}

final class RetryingOps[M[_], A](action: => M[A]) {
  @deprecated("Use retryingOnFailures instead", "2.1.0")
  def retryingM[E](
      wasSuccessful: A => M[Boolean],
      policy: RetryPolicy[M],
      onFailure: (A, RetryDetails) => M[Unit]
  )(implicit M: Monad[M], S: Sleep[M]): M[A] =
    retryingOnFailures(wasSuccessful, policy, onFailure)

  def retryingOnFailures[E](
      wasSuccessful: A => M[Boolean],
      policy: RetryPolicy[M],
      onFailure: (A, RetryDetails) => M[Unit]
  )(implicit M: Monad[M], S: Sleep[M]): M[A] =
    retry_.retryingOnFailures(
      policy = policy,
      wasSuccessful = wasSuccessful,
      onFailure = onFailure
    )(action)
}

final class RetryingErrorOps[M[_], A, E](action: => M[A])(implicit M: MonadError[M, E]) {
  def retryingOnAllErrors(
      policy: RetryPolicy[M],
      onError: (E, RetryDetails) => M[Unit]
  )(implicit S: Sleep[M]): M[A] =
    retry_.retryingOnAllErrors(
      policy = policy,
      onError = onError
    )(action)

  def retryingOnSomeErrors(
      isWorthRetrying: E => M[Boolean],
      policy: RetryPolicy[M],
      onError: (E, RetryDetails) => M[Unit]
  )(implicit S: Sleep[M]): M[A] =
    retry_.retryingOnSomeErrors(
      policy = policy,
      isWorthRetrying = isWorthRetrying,
      onError = onError
    )(action)

  def retryingOnFailuresAndAllErrors(
      wasSuccessful: A => M[Boolean],
      policy: RetryPolicy[M],
      onFailure: (A, RetryDetails) => M[Unit],
      onError: (E, RetryDetails) => M[Unit]
  )(implicit S: Sleep[M]): M[A] =
    retry_.retryingOnFailuresAndAllErrors(
      policy = policy,
      wasSuccessful = wasSuccessful,
      onFailure = onFailure,
      onError = onError
    )(action)

  def retryingOnFailuresAndSomeErrors(
      wasSuccessful: A => M[Boolean],
      isWorthRetrying: E => M[Boolean],
      policy: RetryPolicy[M],
      onFailure: (A, RetryDetails) => M[Unit],
      onError: (E, RetryDetails) => M[Unit]
  )(implicit S: Sleep[M]): M[A] =
    retry_.retryingOnFailuresAndSomeErrors(
      policy = policy,
      wasSuccessful = wasSuccessful,
      isWorthRetrying = isWorthRetrying,
      onFailure = onFailure,
      onError = onError
    )(action)
}

object Fibonacci {
  def fibonacci(n: Int): Long = {
    if (n > 0)
      fib(n)._1
    else
      0
  }

  // "Fast doubling" Fibonacci algorithm.
  // See e.g. http://funloop.org/post/2017-04-14-computing-fibonacci-numbers.html for explanation.
  private def fib(n: Int): (Long, Long) = n match {
    case 0 => (0, 1)
    case m =>
      val (a, b) = fib(m / 2)
      val c = a * (b * 2 - a)
      val d = a * a + b * b
      if (n % 2 == 0)
        (c, d)
      else
        (d, c + d)
  }
}
