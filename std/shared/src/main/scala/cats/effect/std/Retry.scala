package cats.effect.std
package retry

import cats._
import cats.syntax.all._
import cats.arrow.FunctionK
import cats.kernel.BoundedSemilattice
import cats.effect.kernel.Temporal
import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.Random
import java.util.concurrent.TimeUnit

abstract class Retry[F[_]] {
  def nextRetry(status: Retry.Status): F[Retry.Decision]

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

    /**
   * Set an upper bound on any individual delay produced by the given policy.
   */
  def capDelay(cap: FiniteDuration): Retry[F]

  /**
    * Add an upper bound to a policy such that once the given time-delay amount <b>per try</b>
    * has been reached or exceeded, the policy will stop retrying and give up. If you need to
    * stop retrying once <b>cumulative</b> delay reaches a time-delay amount, use
    * [[limitRetriesByCumulativeDelay]].
    */
  def limitRetriesByDelay(threshold: FiniteDuration): Retry[F]

  /**
   * Add an upperbound to a policy such that once the cumulative delay over all retries has
   * reached or exceeded the given limit, the policy will stop retrying and give up.
   */
  def limitRetriesByCumulativeDelay(threshold: FiniteDuration): Retry[F]

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

  sealed trait Decision
  object Decision {
    case object GiveUp extends Decision
    final case class DelayAndRetry(delay: FiniteDuration) extends Decision
  }

  import Decision._

  val noRetriesYet = Retry.Status(0, Duration.Zero, None)

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

  def retry[F[_]: Temporal, A](
    policy: Retry[F],
    action: F[A],
    wasSuccessful: A => F[Boolean],
    isWorthRetrying: Throwable => F[Boolean],
    onFailure: (A, RetryDetails) => F[Unit],
    onError: (Throwable, RetryDetails) => F[Unit]
  ): F[A] = {

    def applyPolicy(
      policy: Retry[F],
      retryStatus: Retry.Status
    ): F[NextStep] =
      policy.nextRetry(retryStatus).map {
        case Decision.DelayAndRetry(delay) =>
          NextStep.RetryAfterDelay(delay, retryStatus.addRetry(delay))
        case Decision.GiveUp =>
          NextStep.GiveUp
    }

    def buildRetryDetails(
      currentStatus: Retry.Status,
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

    sealed trait NextStep

    object NextStep {
      case object GiveUp extends NextStep

      final case class RetryAfterDelay(
        delay: FiniteDuration,
        updatedStatus: Retry.Status
      ) extends NextStep
    }

    def retryingOnFailuresImpl(
      policy: Retry[F],
      wasSuccessful: A => F[Boolean],
      onFailure: (A, RetryDetails) => F[Unit],
      status: Retry.Status,
      a: A
    ): F[Either[Retry.Status, A]] = {

      def onFalse: F[Either[Retry.Status, A]] = for {
        nextStep <- applyPolicy(policy, status)
        _ <- onFailure(a, buildRetryDetails(status, nextStep))
        result <- nextStep match {
          case NextStep.RetryAfterDelay(delay, updatedStatus) =>
            Temporal[F].sleep(delay) *>
            updatedStatus.asLeft.pure[F] // continue recursion
          case NextStep.GiveUp =>
            a.asRight.pure[F] // stop the recursion
        }
      } yield result

      wasSuccessful(a).ifM(
        a.asRight.pure[F],
        onFalse
      )
    }

    def retryingOnSomeErrorsImpl[A](
      policy: Retry[F],
      isWorthRetrying: Throwable => F[Boolean],
      onError: (Throwable, RetryDetails) => F[Unit],
      status: Retry.Status,
      attempt: Either[Throwable, A]
    ): F[Either[Retry.Status, A]] = attempt match {
      case Left(error) =>
        isWorthRetrying(error).ifM(
          for {
            nextStep <- applyPolicy(policy, status)
            _ <- onError(error, buildRetryDetails(status, nextStep))
            result <- nextStep match {
              case NextStep.RetryAfterDelay(delay, updatedStatus) =>
                Temporal[F].sleep(delay) *>
                updatedStatus.asLeft.pure[F] // continue recursion
              case NextStep.GiveUp =>
                Temporal[F].raiseError[A](error).map(Right(_)) // stop the recursion
            }
          } yield result,
          Temporal[F].raiseError[A](error).map(Right(_)) // stop the recursion
        )
      case Right(success) =>
        success.asRight.pure[F] // stop the recursion
    }


    Temporal[F].tailRecM(Retry.noRetriesYet) { status =>
      action.attempt.flatMap {
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

  def apply[F[_]: Monad](
    nextRetry: Retry.Status => F[Retry.Decision],
    pretty: String = "<retry>"
  ): Retry[F] =
    new RetryImpl[F](nextRetry, pretty)

  def lift[F[_]: Monad](
    nextRetry: Retry.Status => Retry.Decision,
    pretty: String = "<retry>"
  ): Retry[F] =
    apply[F](
      status => nextRetry(status).pure[F],
      pretty
    )

  /**
   * Don't retry at all and always give up. Only really useful for combining with other
   * policies.
   */
  def alwaysGiveUp[F[_]: Monad]: Retry[F] =
    Retry.lift[F](Function.const(GiveUp), "alwaysGiveUp")

  /**
   * Delay by a constant amount before each retry. Never give up.
   */
  def constantDelay[F[_]: Monad](delay: FiniteDuration): Retry[F] =
    Retry.lift[F](
      Function.const(DelayAndRetry(delay)),
      show"constantDelay($delay)"
    )

  /**
   * Each delay is twice as long as the previous one. Never give up.
   */
  def exponentialBackoff[F[_]: Monad](
      baseDelay: FiniteDuration
  ): Retry[F] =
    Retry.lift[F](
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
  def limitRetries[F[_]: Monad](maxRetries: Int): Retry[F] =
    Retry.lift[F](
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
  def fibonacciBackoff[F[_]: Monad](
      baseDelay: FiniteDuration
  ): Retry[F] =
    Retry.lift[F](
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
  def fullJitter[F[_]: Monad](baseDelay: FiniteDuration): Retry[F] =
    Retry.lift[F](
      { status =>
        val e = Math.pow(2, status.retriesSoFar).toLong
        val maxDelay = safeMultiply(baseDelay, e)
        val delayNanos = (maxDelay.toNanos * Random.nextDouble()).toLong
        DelayAndRetry(new FiniteDuration(delayNanos, TimeUnit.NANOSECONDS))
      },
      show"fullJitter(baseDelay=$baseDelay)"
    )

// implicit def boundedSemilatticeForRetry[F[_]](
//     implicit F: Monad[F]): BoundedSemilattice[Retry[F]] =
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
    val longMax: BigInt = BigInt(Long.MaxValue)
    val durationNanos = BigInt(duration.toNanos)
    val resultNanos = durationNanos * BigInt(multiplier)
    val safeResultNanos = resultNanos min longMax
    FiniteDuration(safeResultNanos.toLong, TimeUnit.NANOSECONDS)
  }


  private final case class RetryImpl[F[_]: Monad](nextRetry_ : Retry.Status => F[Retry.Decision], pretty: String) extends Retry[F] {

    override def nextRetry(status: Retry.Status): F[Retry.Decision] =
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
          case GiveUp => GiveUp.pure[F].widen[Retry.Decision]
          case DelayAndRetry(d) => f(d).map(DelayAndRetry(_))
        },
        s"$this.flatMapDelay(<function>)"
      )

    def capDelay(cap: FiniteDuration): Retry[F] =
      meet(constantDelay(cap))

    // TODO inline these decideNextRetry definitions
    def limitRetriesByDelay(threshold: FiniteDuration): Retry[F] = {
      def decideNextRetry(status: Retry.Status): F[Retry.Decision] =
        nextRetry(status).map {
          case r @ DelayAndRetry(delay) =>
            if (delay > threshold) GiveUp else r
          case GiveUp => GiveUp
        }

      Retry[F](
        decideNextRetry,
        s"limitRetriesByDelay(threshold=$threshold, $this)"
      )
    }

    def limitRetriesByCumulativeDelay(threshold: FiniteDuration): Retry[F] = {
      def decideNextRetry(status: Retry.Status): F[Retry.Decision] =
        nextRetry(status).map {
          case r @ DelayAndRetry(delay) =>
            if (status.cumulativeDelay + delay >= threshold) GiveUp else r
          case GiveUp => GiveUp
        }

      Retry[F](
        decideNextRetry,
        s"limitRetriesByCumulativeDelay(threshold=$threshold, $this)"
      )
    }

    def mapK[G[_]: Monad](f: F ~> G): Retry[G] =
      Retry(
        status => f(nextRetry(status)),
        s"$this.mapK(<FunctionK>)"
      )
   }
 }

