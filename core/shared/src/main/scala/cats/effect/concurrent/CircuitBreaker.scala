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

package cats.effect.concurrent

import scala.concurrent.duration._

import cats.effect.{Clock, Sync}
import cats.implicits._

import java.util.concurrent.TimeUnit

/** The `CircuitBreaker` is used to provide stability and prevent
 * cascading failures in distributed systems.
 *
 * =Purpose=
 *
 * As an example, we have a web application interacting with a remote
 * third party web service. Let's say the third party has oversold
 * their capacity and their database melts down under load. Assume
 * that the database fails in such a way that it takes a very long
 * time to hand back an error to the third party web service. This in
 * turn makes calls fail after a long period of time.  Back to our
 * web application, the users have noticed that their form
 * submissions take much longer seeming to hang. Well the users do
 * what they know to do which is use the refresh button, adding more
 * requests to their already running requests.  This eventually
 * causes the failure of the web application due to resource
 * exhaustion. This will affect all users, even those who are not
 * using functionality dependent on this third party web service.
 *
 * Introducing circuit breakers on the web service call would cause
 * the requests to begin to fail-fast, letting the user know that
 * something is wrong and that they need not refresh their
 * request. This also confines the failure behavior to only those
 * users that are using functionality dependent on the third party,
 * other users are no longer affected as there is no resource
 * exhaustion. Circuit breakers can also allow savvy developers to
 * mark portions of the site that use the functionality unavailable,
 * or perhaps show some cached content as appropriate while the
 * breaker is open.
 *
 * =How It Works=
 *
 * The circuit breaker models a concurrent state machine that
 * can be in any of these 3 states:
 *
 *  1. [[CircuitBreaker$.Closed Closed]]: During normal
 *     operations or when the `CircuitBreaker` starts
 *    - Exceptions increment the `failures` counter
 *    - Successes reset the failure count to zero
 *    - When the `failures` counter reaches the `maxFailures` count,
 *      the breaker is tripped into `Open` state
 *
 *  1. [[CircuitBreaker$.Open Open]]: The circuit breaker rejects
 *     all tasks with an
 *     [[CircuitBreaker$.RejectedExecution RejectedExecution]]
 *    - all tasks fail fast with `RejectedExecution`
 *    - after the configured `resetTimeout`, the circuit breaker
 *      enters a [[CircuitBreaker$.HalfOpen$ HalfOpen]] state,
 *      allowing one task to go through for testing the connection
 *
 *  1. [[CircuitBreaker$.HalfOpen$ HalfOpen]]: The circuit breaker
 *     has already allowed a task to go through, as a reset attempt,
 *     in order to test the connection
 *    - The first task when `Open` has expired is allowed through
 *      without failing fast, just before the circuit breaker is
 *      evolved into the `HalfOpen` state
 *    - All tasks attempted in `HalfOpen` fail-fast with an exception
 *      just as in [[CircuitBreaker$.Open Open]] state
 *    - If that task attempt succeeds, the breaker is reset back to
 *      the `Closed` state, with the `resetTimeout` and the
 *      `failures` count also reset to initial values
 *    - If the first call fails, the breaker is tripped again into
 *      the `Open` state (the `resetTimeout` is multiplied by the
 *      exponential backoff factor)
 *
 * =Usage=
 *
 * {{{
 *   import cats.effect._
 *   import cats.effect.concurrent.CircuitBreaker
 *   import scala.concurrent.duration._
 *
 *   val circuitBreaker = CircuitBreaker[IO].of(
 *     maxFailures = 5,
 *     resetTimeout = 10.seconds
 *   )
 *
 *   //...
 *   val problematic = IO {
 *     val nr = util.Random.nextInt()
 *     if (nr % 2 == 0) nr else
 *       throw new RuntimeException("dummy")
 *   }
 *
 *   val task = circuitBreaker
 *     .flatMap(_.protect(problematic))
 * }}}
 *
 * When attempting to close the circuit breaker and resume normal
 * operations, we can also apply an exponential backoff for repeated
 * failed attempts, like so:
 *
 * {{{
 *   val exponential = CircuitBreaker[IO].of(
 *     maxFailures = 5,
 *     resetTimeout = 10.seconds,
 *     exponentialBackoffFactor = 2,
 *     maxResetTimeout = 10.minutes
 *   )
 * }}}
 *
 * In this sample we attempt to reconnect after 10 seconds, then after
 * 20, 40 and so on, a delay that keeps increasing up to a configurable
 * maximum of 10 minutes.
 *
 * =Credits=
 *
 * This data type was inspired by the availability of
 * [[http://doc.akka.io/docs/akka/current/common/circuitbreaker.html Akka's Circuit Breaker]]
 * and ported to cats-effect from [[https://monix.io Monix]]
 */
trait CircuitBreaker[F[_]] {
  /** Returns a new effect that upon execution will execute the given
   * effect with the protection of this circuit breaker.
   */
  def protect[A](fa: F[A]): F[A]

  /** Returns a new circuit breaker that wraps the state of the source
   * and that will fire the given callback upon the circuit breaker
   * transitioning to the [[CircuitBreaker.Open Open]] state.
   *
   * Useful for gathering stats.
   *
   * NOTE: calling this method multiple times will create a circuit
   * breaker that will call multiple callbacks, thus the callback
   * given is cumulative with other specified callbacks.
   *
   * @param callback is to be executed when the state evolves into `Open`
   * @return a new circuit breaker wrapping the state of the source
   */
  def doOnOpen(callback: F[Unit]): CircuitBreaker[F]

  /** Returns a new circuit breaker that wraps the state of the source
   * and that upon a task being rejected will execute the given
   * `callback`.
   *
   * Useful for gathering stats.
   *
   * NOTE: calling this method multiple times will create a circuit
   * breaker that will call multiple callbacks, thus the callback
   * given is cumulative with other specified callbacks.
   *
   * @param callback is to be executed when tasks get rejected
   * @return a new circuit breaker wrapping the state of the source
   */
  def doOnRejected(callback: F[Unit]): CircuitBreaker[F]


  /** Returns a new circuit breaker that wraps the state of the source
   * and that will fire the given callback upon the circuit breaker
   * transitioning to the [[CircuitBreaker.HalfOpen HalfOpen]]
   * state.
   *
   * Useful for gathering stats.
   *
   * NOTE: calling this method multiple times will create a circuit
   * breaker that will call multiple callbacks, thus the callback
   * given is cumulative with other specified callbacks.
   *
   * @param callback is to be executed when the state evolves into `HalfOpen`
   * @return a new circuit breaker wrapping the state of the source
   */
  def doOnHalfOpen(callback: F[Unit]): CircuitBreaker[F]

  /** Returns a new circuit breaker that wraps the state of the source
   * and that will fire the given callback upon the circuit breaker
   * transitioning to the [[CircuitBreaker.Closed Closed]] state.
   *
   * Useful for gathering stats.
   *
   * NOTE: calling this method multiple times will create a circuit
   * breaker that will call multiple callbacks, thus the callback
   * given is cumulative with other specified callbacks.
   *
   * @param callback is to be executed when the state evolves into `Closed`
   * @return a new circuit breaker wrapping the state of the source
   */
  def doOnClosed(callback: F[Unit]): CircuitBreaker[F]

  /** Returns the current [[CircuitBreaker.State]], meant for
   * debugging purposes.
   */
  def state: F[CircuitBreaker.State]
}

object CircuitBreaker {
  /** Builder for a [[CircuitBreaker]] reference.
   *
   * Effect returned by this operation produces a new
   * [[CircuitBreaker]] each time it is evaluated. To share a state between
   * multiple consumers, pass [[CircuitBreaker]] as a parameter
   *
   * @param maxFailures is the maximum count for failures before
   *        opening the circuit breaker
   * @param resetTimeout is the timeout to wait in the `Open` state
   *        before attempting a close of the circuit breaker (but
   *        without the backoff factor applied)
   * @param exponentialBackoffFactor is a factor to use for resetting
   *        the `resetTimeout` when in the `HalfOpen` state, in case
   *        the attempt to `Close` fails
   * @param maxResetTimeout is the maximum timeout the circuit breaker
   *        is allowed to use when applying the `exponentialBackoffFactor`
   */
  def of[F[_]](
    maxFailures: Int,
    resetTimeout: FiniteDuration,
    exponentialBackoffFactor: Double = 1,
    maxResetTimeout: Duration = Duration.Inf
  )(implicit F: Sync[F], clock: Clock[F]): F[CircuitBreaker[F]] = {
    of(maxFailures, resetTimeout, exponentialBackoffFactor, maxResetTimeout, F.unit, F. unit, F.unit, F.unit)
  }

  /** Builder for a [[CircuitBreaker]] reference.
   *
   * Effect returned by this operation produces a new
   * [[CircuitBreaker]] each time it is evaluated. To share a state between
   * multiple consumers, pass [[CircuitBreaker]] as a parameter
   *
   * @param maxFailures is the maximum count for failures before
   *        opening the circuit breaker
   * @param resetTimeout is the timeout to wait in the `Open` state
   *        before attempting a close of the circuit breaker (but
   *        without the backoff factor applied)
   * @param exponentialBackoffFactor is a factor to use for resetting
   *        the `resetTimeout` when in the `HalfOpen` state, in case
   *        the attempt to `Close` fails
   * @param maxResetTimeout is the maximum timeout the circuit breaker
   *        is allowed to use when applying the `exponentialBackoffFactor`
   *
   * @param onRejected is for signaling rejected tasks
   * @param onClosed is for signaling a transition to `Closed`
   * @param onHalfOpen is for signaling a transition to `HalfOpen`
   * @param onOpen is for signaling a transition to `Open`
   */
  def of[F[_]](
    maxFailures: Int,
    resetTimeout: FiniteDuration,
    exponentialBackoffFactor: Double,
    maxResetTimeout: Duration,
    onRejected: F[Unit],
    onClosed: F[Unit],
    onHalfOpen: F[Unit],
    onOpen: F[Unit]
  )(implicit F: Sync[F], clock: Clock[F]): F[CircuitBreaker[F]] =
    Ref.of[F, State](ClosedZero).map { ref =>
      new SyncCircuitBreaker[F](
        ref,
        maxFailures,
        resetTimeout,
        exponentialBackoffFactor,
        maxResetTimeout,
        onRejected,
        onClosed,
        onHalfOpen,
        onOpen
      )
    }

  /** Type-alias to document timestamps specified in milliseconds, as returned by
   * [[cats.effect.Clock.realTime Clock.realTime]].
   */
  type Timestamp = Long

  /** An enumeration that models the internal state of [[CircuitBreaker]],
   * kept in an `AtomicReference` for synchronization.
   *
   * The initial state when initializing a [[CircuitBreaker]] is
   * [[Closed]]. The available states:
   *
   *  - [[Closed]] in case tasks are allowed to go through
   *  - [[Open]] in case the circuit breaker is active and rejects incoming tasks
   *  - [[HalfOpen]] in case a reset attempt was triggered and it is waiting for
   *    the result in order to evolve in [[Closed]], or back to [[Open]]
   */
  sealed abstract class State

  sealed trait Reason

  /** The initial [[State]] of the [[CircuitBreaker]]. While in this
   * state the circuit breaker allows tasks to be executed.
   *
   * Contract:
   *
   *  - Exceptions increment the `failures` counter
   *  - Successes reset the failure count to zero
   *  - When the `failures` counter reaches the `maxFailures` count,
   *    the breaker is tripped into the `Open` state
   *
   * @param failures is the current failures count
   */
  final case class Closed(failures: Int) extends State

  /** [[State]] of the [[CircuitBreaker]] in which the circuit
   * breaker rejects all tasks with a [[RejectedExecution]].
   *
   * Contract:
   *
   *  - all tasks fail fast with `RejectedExecution`
   *  - after the configured `resetTimeout`, the circuit breaker
   *    enters a [[HalfOpen]] state, allowing one task to go through
   *    for testing the connection
   *
   * @param startedAt is the timestamp in milliseconds since the
   *        epoch when the transition to `Open` happened
   * @param resetTimeout is the current `resetTimeout` that is
   *        applied to this `Open` state, to be multiplied by the
   *        exponential backoff factor for the next transition from
   *        `HalfOpen` to `Open`, in case the reset attempt fails
   */
  final case class Open(startedAt: Timestamp, resetTimeout: FiniteDuration) extends State with Reason {
    /** The timestamp in milliseconds since the epoch, specifying
     * when the `Open` state is to transition to [[HalfOpen]].
     *
     * It is calculated as:
     * ```scala
     *   startedAt + resetTimeout.toMillis
     * ```
     */
    val expiresAt: Timestamp = startedAt + resetTimeout.toMillis
  }

  /** [[State]] of the [[CircuitBreaker]] in which the circuit
   * breaker has already allowed a task to go through, as a reset
   * attempt, in order to test the connection.
   *
   * Contract:
   *
   *  - The first task when `Open` has expired is allowed through
   *    without failing fast, just before the circuit breaker is
   *    evolved into the `HalfOpen` state
   *  - All tasks attempted in `HalfOpen` fail-fast with an exception
   *    just as in [[Open]] state
   *  - If that task attempt succeeds, the breaker is reset back to
   *    the `Closed` state, with the `resetTimeout` and the
   *    `failures` count also reset to initial values
   *  - If the first call fails, the breaker is tripped again into
   *    the `Open` state (the `resetTimeout` is multiplied by the
   *    exponential backoff factor)
   */
  case object HalfOpen extends State with Reason

  private val ClosedZero = Closed(0)


  /** Exception thrown whenever an execution attempt was rejected.
   */
  final case class RejectedExecution (reason: Reason)
    extends RuntimeException(s"Execution rejected: $reason")

  private final class SyncCircuitBreaker[F[_]] (
    ref: Ref[F, CircuitBreaker.State],
    maxFailures: Int,
    resetTimeout: FiniteDuration,
    exponentialBackoffFactor: Double,
    maxResetTimeout: Duration,
    onRejected: F[Unit],
    onClosed: F[Unit],
    onHalfOpen: F[Unit],
    onOpen: F[Unit]
  )(
    implicit F: Sync[F],
    clock: Clock[F]
  ) extends CircuitBreaker[F] {

    require(maxFailures >= 0, "maxFailures >= 0")
    require(exponentialBackoffFactor >= 1, "exponentialBackoffFactor >= 1")
    require(resetTimeout > Duration.Zero, "resetTimeout > 0")
    require(maxResetTimeout > Duration.Zero, "maxResetTimeout > 0")


    def state: F[CircuitBreaker.State] =
      ref.get


    def doOnRejected(callback: F[Unit]): CircuitBreaker[F] = {
      val onRejected = this.onRejected.flatMap(_ => callback)
      new SyncCircuitBreaker(
        ref = ref,
        maxFailures = maxFailures,
        resetTimeout = resetTimeout,
        exponentialBackoffFactor = exponentialBackoffFactor,
        maxResetTimeout = maxResetTimeout,
        onRejected = onRejected,
        onClosed = onClosed,
        onHalfOpen = onHalfOpen,
        onOpen = onOpen)
    }

    def doOnClosed(callback: F[Unit]): CircuitBreaker[F] = {
      val onClosed = this.onClosed.flatMap(_ => callback)
      new SyncCircuitBreaker(
        ref = ref,
        maxFailures = maxFailures,
        resetTimeout = resetTimeout,
        exponentialBackoffFactor = exponentialBackoffFactor,
        maxResetTimeout = maxResetTimeout,
        onRejected = onRejected,
        onClosed = onClosed,
        onHalfOpen = onHalfOpen,
        onOpen = onOpen)
    }

    def doOnHalfOpen(callback: F[Unit]): CircuitBreaker[F] = {
      val onHalfOpen = this.onHalfOpen.flatMap(_ => callback)
      new SyncCircuitBreaker(
        ref = ref,
        maxFailures = maxFailures,
        resetTimeout = resetTimeout,
        exponentialBackoffFactor = exponentialBackoffFactor,
        maxResetTimeout = maxResetTimeout,
        onRejected = onRejected,
        onClosed = onClosed,
        onHalfOpen = onHalfOpen,
        onOpen = onOpen)
    }


    def doOnOpen(callback: F[Unit]): CircuitBreaker[F] = {
      val onOpen = this.onOpen.flatMap(_ => callback)
      new SyncCircuitBreaker(
        ref = ref,
        maxFailures = maxFailures,
        resetTimeout = resetTimeout,
        exponentialBackoffFactor = exponentialBackoffFactor,
        maxResetTimeout = maxResetTimeout,
        onRejected = onRejected,
        onClosed = onClosed,
        onHalfOpen = onHalfOpen,
        onOpen = onOpen)
    }


    def openOnFail[A](f: F[A]): F[A] = {
      f.attempt.flatMap {
        case Right(a) =>
          ref.set(ClosedZero) as a

        case Left(err) =>
          clock.monotonic(TimeUnit.MILLISECONDS).flatMap { now =>
            ref.modify {
              case Closed(failures) =>
                val count = failures + 1
                if (count >= maxFailures) (Open(now, resetTimeout), onOpen >> F.raiseError[A](err))
                else (Closed(count), F.raiseError[A](err))
              case open: Open => (open, F.raiseError[A](err))
              case HalfOpen => (HalfOpen, F.raiseError[A](err))
            }.flatten
          }
      }
    }

    def backoff(open:Open): Open = {
      def next = (open.resetTimeout.toMillis * exponentialBackoffFactor).millis
      open.copy(
        resetTimeout = maxResetTimeout match {
          case fin: FiniteDuration => next min fin
          case _: Duration => next
        }
      )
    }

    def tryReset[A](open:Open,fa: F[A]): F[A] = {
      clock.monotonic(TimeUnit.MILLISECONDS).flatMap { now =>
        if (open.startedAt + open.resetTimeout.toMillis >= now) onRejected >> F.raiseError(RejectedExecution(open))
        else {
          def resetOnSuccess: F[A] = {
            fa.attempt.flatMap {
              case Left(err) => ref.set(backoff(open)) >> F.raiseError(err)
              case Right(a) => onClosed >> ref.set(ClosedZero) as a
            }
          }
          ref.modify {
            case closed: Closed => (closed, openOnFail(fa))
            case open@Open(startedAt, resetTimeout) =>
              if (startedAt == open.startedAt && open.resetTimeout == resetTimeout) (HalfOpen, onHalfOpen >> resetOnSuccess)
              else (open, onRejected >> F.raiseError[A](RejectedExecution(open)))
            case HalfOpen => (HalfOpen, onRejected >> F.raiseError[A](RejectedExecution(HalfOpen)))
          }.flatten

        }
      }
    }

    def protect[A](fa: F[A]): F[A] = {
      ref.modify {
        case closed: Closed  => (closed, openOnFail(fa))
        case open: Open  => (open, tryReset(open, fa))
        case HalfOpen => (HalfOpen,  onRejected >> F.raiseError[A](RejectedExecution(HalfOpen)))
      }.flatten
    }
  }
}
