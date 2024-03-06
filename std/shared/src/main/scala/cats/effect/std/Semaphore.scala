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

package cats
package effect
package std

import cats.effect.kernel._
import cats.effect.kernel.syntax.all._
import cats.syntax.all._

import scala.annotation.tailrec
import scala.collection.immutable.{Queue => Q}

/**
 * A purely functional semaphore.
 *
 * A semaphore has a non-negative number of permits available. Acquiring a permit decrements the
 * current number of permits and releasing a permit increases the current number of permits. An
 * acquire that occurs when there are no permits available results in fiber blocking until a
 * permit becomes available.
 */
abstract class Semaphore[F[_]] {

  /**
   * Returns the number of permits currently available. Always non-negative.
   *
   * May be out of date the instant after it is retrieved. Use `[[tryAcquire]]` or
   * `[[tryAcquireN]]` if you wish to attempt an acquire, returning immediately if the current
   * count is not high enough to satisfy the request.
   */
  def available: F[Long]

  /**
   * Obtains a snapshot of the current count. May be negative.
   *
   * Like [[available]] when permits are available but returns the number of permits callers are
   * waiting for when there are no permits available.
   */
  def count: F[Long]

  /**
   * Acquires `n` permits.
   *
   * The returned effect semantically blocks until all requested permits are available. Note
   * that acquires are statisfied in strict FIFO order, so given `s: Semaphore[F]` with 2
   * permits available, an `acquireN(3)` will always be satisfied before a later call to
   * `acquireN(1)`.
   *
   * This method is interruptible, and in case of interruption it will take care of restoring
   * any permits it has acquired. If it does succeed however, managing permits correctly is the
   * user's responsibility. Use `[[permit]]` for a safer but less flexible alternative, when you
   * are using Semaphore merely as a lock.
   *
   * @param n
   *   number of permits to acquire - must be >= 0
   */
  def acquireN(n: Long): F[Unit]

  /**
   * Acquires a single permit. Alias for `[[acquireN]](1)`.
   */
  def acquire: F[Unit] = acquireN(1)

  /**
   * Acquires `n` permits now and returns `true`, or returns `false` immediately. Error if `n <
   * 0`.
   *
   * @param n
   *   number of permits to acquire - must be >= 0
   */
  def tryAcquireN(n: Long): F[Boolean]

  /**
   * Alias for `[[tryAcquireN]](1)`.
   */
  def tryAcquire: F[Boolean] = tryAcquireN(1)

  /**
   * Releases `n` permits, potentially unblocking up to `n` outstanding acquires.
   *
   * @param n
   *   number of permits to release - must be >= 0
   */
  def releaseN(n: Long): F[Unit]

  /**
   * Releases a single permit. Alias for `[[releaseN]](1)`.
   */
  def release: F[Unit] = releaseN(1)

  /**
   * Returns a [[cats.effect.kernel.Resource]] that acquires a permit, holds it for the lifetime
   * of the resource, then releases the permit.
   */
  def permit: Resource[F, Unit]

  /**
   * Returns a [[cats.effect.kernel.Resource]] that contains a boolean that indicates whether we
   * acquired a permit or not. If the permit was acquired then it is guaranteed to be released
   * at the end of the Resource lifetime
   */
  def tryPermit(implicit F: Applicative[F]): Resource[F, Boolean] =
    Resource.make(tryAcquire) { acquired => release.whenA(acquired) }

  /**
   * Modify the context `F` using natural transformation `f`.
   */
  def mapK[G[_]](f: F ~> G)(implicit G: MonadCancel[G, _]): Semaphore[G]
}

object Semaphore {

  /**
   * Creates a new `Semaphore`, initialized with `n` available permits.
   */
  def apply[F[_]](n: Long)(implicit F: GenConcurrent[F, _]): F[Semaphore[F]] = {
    val impl = new impl[F](n)
    F.ref(impl.initialState).map(impl.semaphore)
  }

  /**
   * Creates a new `Semaphore`, initialized with `n` available permits. like `apply` but
   * initializes state using another effect constructor
   */
  def in[F[_], G[_]](n: Long)(implicit F: Sync[F], G: Async[G]): F[Semaphore[G]] = {
    val impl = new impl[G](n)
    Ref.in[F, G, impl.State](impl.initialState).map(impl.semaphore)

  }

  private class impl[F[_]](n: Long)(implicit F: GenConcurrent[F, _]) {
    requireNonNegative(n)

    def requireNonNegative(n: Long): Unit =
      require(n >= 0, s"n must be nonnegative, was: $n")

    case class Request(n: Long, gate: Deferred[F, Unit]) {
      // the number of permits can change if the request is partially fulfilled
      def sameAs(r: Request) = r.gate eq gate
      def of(newN: Long) = Request(newN, gate)
      def wait_ = gate.get
      def complete = gate.complete(())
    }
    def newRequest = F.deferred[Unit].map(Request(0, _))

    /*
     * Invariant:
     *    (waiting.empty && permits >= 0) || (waiting.nonEmpty && permits == 0)
     */
    case class State(permits: Long, waiting: Q[Request])
    def initialState = State(n, Q())

    sealed trait Action
    case object Wait extends Action
    case object Done extends Action

    def semaphore(state: Ref[F, State]) =
      new Semaphore[F] { self =>
        def acquireN(n: Long): F[Unit] = {
          requireNonNegative(n)

          if (n == 0) F.unit
          else
            F.uncancelable { poll =>
              newRequest.flatMap { req =>
                state.modify {
                  case State(permits, waiting) =>
                    val (newState, decision) =
                      if (waiting.nonEmpty) State(0, waiting enqueue req.of(n)) -> Wait
                      else {
                        val diff = permits - n
                        if (diff >= 0) State(diff, Q()) -> Done
                        else State(0, req.of(diff.abs).pure[Q]) -> Wait
                      }

                    val cleanup = state.modify {
                      case State(permits, waiting) =>
                        // both hold correctly even if the Request gets canceled
                        // after having been fulfilled
                        val permitsAcquiredSoFar = n - waiting.find(_ sameAs req).foldMap(_.n)
                        val waitingNow = waiting.filterNot(_ sameAs req)
                        // releaseN is commutative, the separate Ref access is ok
                        State(permits, waitingNow) -> releaseN(permitsAcquiredSoFar)
                    }.flatten

                    val action = decision match {
                      case Done => F.unit
                      case Wait => poll(req.wait_).onCancel(cleanup)
                    }

                    newState -> action
                }.flatten
              }
            }
        }

        def releaseN(n: Long): F[Unit] = {
          requireNonNegative(n)

          @tailrec
          def fulfil(
              n: Long,
              requests: Q[Request],
              wakeup: Q[Request]): (Long, Q[Request], Q[Request]) = {
            val (req, tail) = requests.dequeue
            if (n < req.n) // partially fulfil one request
              (0, req.of(req.n - n) +: tail, wakeup)
            else { // fulfil as many requests as `n` allows
              val newN = n - req.n
              val newWakeup = wakeup.enqueue(req)

              if (tail.isEmpty || newN == 0) (newN, tail, newWakeup)
              else fulfil(newN, tail, newWakeup)
            }
          }

          if (n == 0) F.unit
          else
            state.flatModify {
              case State(permits, waiting) =>
                if (waiting.isEmpty) State(permits + n, waiting) -> F.unit
                else {
                  val (newN, waitingNow, wakeup) = fulfil(n, waiting, Q())
                  State(newN, waitingNow) -> wakeup.traverse_(_.complete)
                }
            }
        }

        def available: F[Long] = state.get.map(_.permits)

        def count: F[Long] =
          state.get.map {
            case State(permits, waiting) =>
              if (waiting.nonEmpty) -waiting.foldMap(_.n)
              else permits
          }

        def permit: Resource[F, Unit] =
          Resource.makeFull { (poll: Poll[F]) => poll(acquire) } { _ => release }

        def tryAcquireN(n: Long): F[Boolean] = {
          requireNonNegative(n)
          if (n == 0) F.pure(true)
          else
            state.modify { state =>
              val permits = state.permits
              if (permits >= n) state.copy(permits = permits - n) -> true
              else state -> false
            }
        }

        def mapK[G[_]](f: F ~> G)(implicit G: MonadCancel[G, _]): Semaphore[G] =
          new MapKSemaphore[F, G](this, f)
      }
  }

  final private[std] class MapKSemaphore[F[_], G[_]](
      underlying: Semaphore[F],
      f: F ~> G
  )(implicit F: MonadCancel[F, _], G: MonadCancel[G, _])
      extends Semaphore[G] {
    def available: G[Long] = f(underlying.available)
    def count: G[Long] = f(underlying.count)
    def acquireN(n: Long): G[Unit] = f(underlying.acquireN(n))
    def tryAcquireN(n: Long): G[Boolean] = f(underlying.tryAcquireN(n))
    def releaseN(n: Long): G[Unit] = f(underlying.releaseN(n))
    def permit: Resource[G, Unit] = underlying.permit.mapK(f)
    def mapK[H[_]](f: G ~> H)(implicit H: MonadCancel[H, _]): Semaphore[H] =
      new MapKSemaphore(this, f)
  }
}
