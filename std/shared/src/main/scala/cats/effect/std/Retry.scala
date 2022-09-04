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
  def nextRetry(status: Retry.Status, error: Throwable): F[Retry.Decision]

  def followedBy(r: Retry[F]): Retry[F]

  /**
   * Combine this schedule with another schedule, retrying when both schedules want to retry
   * and choosing the maximum of the two delays.
   */
  def and(r: Retry[F]): Retry[F]

  /**
   * Combine this schedule with another schedule, retrying when either schedule wants to retry
   * and choosing the minimum of the two delays when both schedules want to retry.
   */
  def or(r: Retry[F]): Retry[F]

  def mapDelay(f: FiniteDuration => FiniteDuration): Retry[F]

  def flatMapDelay(f: FiniteDuration => F[FiniteDuration]): Retry[F]

    /**
   * Set an upper bound on any individual delay produced by the given Retry.
   */
  def capDelay(cap: FiniteDuration): Retry[F]

  /**
    * Add an upper bound to a Retry such that once the given time-delay amount <b>per try</b>
    * has been reached or exceeded, the Retry will stop retrying and give up. If you need to
    * stop retrying once <b>cumulative</b> delay reaches a time-delay amount, use
    * [[limitRetriesByCumulativeDelay]].
    */
  def limitRetriesByDelay(threshold: FiniteDuration): Retry[F]

  /**
   * Add an upperbound to a Retry such that once the cumulative delay over all retries has
   * reached or exceeded the given limit, the Retry will stop retrying and give up.
   */
  def limitRetriesByCumulativeDelay(threshold: FiniteDuration): Retry[F]

  def selectError(f: Throwable => Boolean): Retry[F]

  def selectErrorWith(f: Throwable => F[Boolean]): Retry[F]

  def flatTap(f: (Throwable, Retry.Decision, Retry.Status) => F[Unit]): Retry[F]

  def mapK[G[_]: Monad](f: F ~> G): Retry[G]
}
object Retry {
  final case class Status(
    retriesSoFar: Int,
    cumulativeDelay: FiniteDuration,
    previousDelay: Option[FiniteDuration]
  ) {
    def addRetry(delay: FiniteDuration) = Retry.Status(
      retriesSoFar = retriesSoFar + 1,
      cumulativeDelay = cumulativeDelay + delay,
      previousDelay = delay.some
    )
  }

  sealed trait Decision
  object Decision {
    case object GiveUp extends Decision
    final case class DelayAndRetry(delay: FiniteDuration) extends Decision
  }

  import Decision._

  val noRetriesYet = Retry.Status(0, Duration.Zero, None)

  def retry[F[_]: Temporal, A](r: Retry[F], action: F[A]): F[A] = {
    def loop(status: Retry.Status): F[A] =
      action.handleErrorWith { error =>
        r
          .nextRetry(status, error)
          .flatMap {
            case DelayAndRetry(delay) =>
              Temporal[F].sleep(delay) >> loop(status.addRetry(delay))
            case GiveUp =>
              Temporal[F].raiseError[A](error)
          }
      }

    loop(noRetriesYet)
  }

  def apply[F[_]: Monad](
    nextRetry: (Retry.Status, Throwable) => F[Retry.Decision]
  ): Retry[F] =
    new RetryImpl[F](nextRetry)

  def liftF[F[_]: Monad](
    nextRetry: Retry.Status => F[Retry.Decision]
  ): Retry[F] =
    Retry((status, _) => nextRetry(status))

  def lift[F[_]: Monad](
    nextRetry: Retry.Status => Retry.Decision
  ): Retry[F] =
    liftF[F](status => nextRetry(status).pure[F])

  /**
   * Don't retry at all and always give up. Only really useful for combining with other
   * policies.
   */
  def alwaysGiveUp[F[_]: Monad]: Retry[F] =
    Retry.lift[F](_ => GiveUp)

  /**
   * Delay by a constant amount before each retry. Never give up.
   */
  def constantDelay[F[_]: Monad](delay: FiniteDuration): Retry[F] =
    Retry.lift[F](_ => DelayAndRetry(delay))

  /**
   * Each delay is twice as long as the previous one. Never give up.
   */
  def exponentialBackoff[F[_]: Monad](
      baseDelay: FiniteDuration
  ): Retry[F] =
    Retry.lift[F] { status =>
      val delay = safeMultiply(baseDelay, Math.pow(2, status.retriesSoFar).toLong)
      DelayAndRetry(delay)
    }


  /**
   * Retry without delay, giving up after the given number of retries.
   */
  def limitRetries[F[_]: Monad](maxRetries: Int): Retry[F] =
    Retry.lift[F] { status =>
        if (status.retriesSoFar >= maxRetries) GiveUp
        else DelayAndRetry(Duration.Zero)
    }

  /**
   * Delay(n) = Delay(n - 2) + Delay(n - 1)
   *
   * e.g. if `baseDelay` is 10 milliseconds, the delays before each retry will be 10 ms, 10 ms,
   * 20 ms, 30ms, 50ms, 80ms, 130ms, ...
   */
  def fibonacciBackoff[F[_]: Monad](
      baseDelay: FiniteDuration
  ): Retry[F] =
    Retry.lift[F] { status =>
        val delay =
          safeMultiply(baseDelay, Fibonacci.fibonacci(status.retriesSoFar + 1))
        DelayAndRetry(delay)
    }

  /**
   * "Full jitter" backoff algorithm. See
   * https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/
   */
  def fullJitter[F[_]: Monad](baseDelay: FiniteDuration): Retry[F] =
    Retry.lift[F] { status =>
      val e = Math.pow(2, status.retriesSoFar).toLong
      val maxDelay = safeMultiply(baseDelay, e)
      val delayNanos = (maxDelay.toNanos * Random.nextDouble()).toLong
      DelayAndRetry(new FiniteDuration(delayNanos, TimeUnit.NANOSECONDS))
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


  private final case class RetryImpl[F[_]: Monad](nextRetry_ : (Retry.Status, Throwable) => F[Retry.Decision]) extends Retry[F] {

    def nextRetry(status: Retry.Status, error: Throwable): F[Retry.Decision] =
      nextRetry_(status, error)

    def followedBy(r: Retry[F]) = Retry { (status, error) =>
      (nextRetry(status, error), r.nextRetry(status, error)).mapN {
        case (GiveUp, decision) => decision
        case (decision, _) => decision
      }
    }

    def and(r: Retry[F]) = Retry[F] { (status, error) =>
      (nextRetry(status, error), r.nextRetry(status, error)).mapN {
        case (DelayAndRetry(t1), DelayAndRetry(t2)) => DelayAndRetry(t1 max t2)
        case _ => GiveUp
      }
    }


    def or(r: Retry[F]) = Retry { (status, error) =>
      (nextRetry(status, error), r.nextRetry(status, error)).mapN {
        case (DelayAndRetry(t1), DelayAndRetry(t2)) => DelayAndRetry(t1 min t2)
        case (retrying @ DelayAndRetry(_), GiveUp) => retrying
        case (GiveUp, retrying @ DelayAndRetry(_)) => retrying
        case _ => GiveUp
      }
    }

    def mapDelay(f: FiniteDuration => FiniteDuration) = Retry { (status, error) =>
      nextRetry(status, error).map {
        case GiveUp => GiveUp
        case DelayAndRetry(delay) => DelayAndRetry(f(delay))
      }
    }

    def flatMapDelay(f: FiniteDuration => F[FiniteDuration]) = Retry { (status, error) =>
      nextRetry(status, error).flatMap {
        case GiveUp => GiveUp.pure[F].widen[Retry.Decision]
        case DelayAndRetry(delay) => f(delay).map(DelayAndRetry(_))
      }
    }

    def capDelay(cap: FiniteDuration): Retry[F] =
      mapDelay(delay => delay.min(cap))

    def limitRetriesByDelay(threshold: FiniteDuration) = Retry { (status, error) =>
      nextRetry(status, error).map {
        case retrying @ DelayAndRetry(delay) =>
          if (delay > threshold) GiveUp else retrying
        case GiveUp => GiveUp
      }
    }

    def limitRetriesByCumulativeDelay(threshold: FiniteDuration) =
      Retry { (status, error) =>
        nextRetry(status, error).map {
          case retrying @ DelayAndRetry(delay) =>
            if (status.cumulativeDelay + delay >= threshold) GiveUp
            else retrying
          case GiveUp => GiveUp
        }
    }

    def selectError(f: Throwable => Boolean): Retry[F] =
      Retry { (status, error) =>
        if(f(error)) nextRetry(status, error)
        else GiveUp.pure[F].widen[Decision]
      }

    def selectErrorWith(f: Throwable => F[Boolean]): Retry[F] =
      Retry { (status, error) =>
        f(error).flatMap {
          case true => nextRetry(status, error)
          case false => GiveUp.pure[F].widen[Decision]
        }
      }

    def flatTap(f: (Throwable, Retry.Decision, Retry.Status) => F[Unit]): Retry[F] =
      Retry { (status, error) =>
        nextRetry(status, error).flatTap {
          case decision @ DelayAndRetry(delay) =>
            f(error, decision, status.addRetry(delay))
          case decision @ GiveUp =>
            f(error, decision, status)
        }
      }

    def mapK[G[_]: Monad](f: F ~> G): Retry[G] =
      Retry((status, error) => f(nextRetry(status, error)))

    override def toString: String = "Retry(...)"
  }
 }
