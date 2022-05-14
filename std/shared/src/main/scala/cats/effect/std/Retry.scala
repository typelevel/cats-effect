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

  val noRetriesYet = Retry.Status(0, Duration.Zero, None)

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

  /**
   * Set an upper bound on any individual delay produced by the given policy.
   */
  def capDelay[F[_]: Monad](
      cap: FiniteDuration,
      policy: Retry[F]
  ): Retry[F] =
    policy.meet(constantDelay(cap))

  /**
   * Add an upper bound to a policy such that once the given time-delay amount <b>per try</b>
   * has been reached or exceeded, the policy will stop retrying and give up. If you need to
   * stop retrying once <b>cumulative</b> delay reaches a time-delay amount, use
   * [[limitRetriesByCumulativeDelay]].
   */
  def limitRetriesByDelay[F[_]: Monad](
      threshold: FiniteDuration,
      policy: Retry[F]
  ): Retry[F] = {
    def decideNextRetry(status: Retry.Status): F[PolicyDecision] =
      policy.nextRetry(status).map {
        case r @ DelayAndRetry(delay) =>
          if (delay > threshold) GiveUp else r
        case GiveUp => GiveUp
      }

    Retry[F](
      decideNextRetry,
      s"limitRetriesByDelay(threshold=$threshold, $policy)"
    )
  }

  /**
   * Add an upperbound to a policy such that once the cumulative delay over all retries has
   * reached or exceeded the given limit, the policy will stop retrying and give up.
   */
  def limitRetriesByCumulativeDelay[F[_]: Monad](
      threshold: FiniteDuration,
      policy: Retry[F]
  ): Retry[F] = {
    def decideNextRetry(status: Retry.Status): F[PolicyDecision] =
      policy.nextRetry(status).map {
        case r @ DelayAndRetry(delay) =>
          if (status.cumulativeDelay + delay >= threshold) GiveUp else r
        case GiveUp => GiveUp
      }

    Retry[F](
      decideNextRetry,
      s"limitRetriesByCumulativeDelay(threshold=$threshold, $policy)"
    )
  }
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
