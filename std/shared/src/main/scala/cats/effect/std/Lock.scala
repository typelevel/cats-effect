/*
 * Copyright 2020-2025 Typelevel
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

import scala.collection.immutable.{Queue => Q}

abstract class Lock[F[_]] { self =>
  def shared: Resource[F, Unit]
  def exclusive: Resource[F, Unit]
  def tryShared: Resource[F, Boolean]
  def tryExclusive: Resource[F, Boolean]
  def mapK[G[_]](fk: F ~> G)(implicit F: MonadCancel[F, ?], G: MonadCancel[G, ?]): Lock[G] =
    new Lock[G] {
      override def shared: Resource[G, Unit] = self.shared.mapK(fk)
      override def exclusive: Resource[G, Unit] = self.exclusive.mapK(fk)
      override def tryShared: Resource[G, Boolean] = self.tryShared.mapK(fk)
      override def tryExclusive: Resource[G, Boolean] = self.tryExclusive.mapK(fk)
    }
}
object Lock {

  /**
   * <p>A simple [[Lock]] built on top of [[Semaphore]].
   *
   * <p>This implementation does '''not''' guarantee writer-priority. Readers can starve writers
   * under high contention.
   */
  def simple[F[_]](maxShared: Long)(implicit F: GenConcurrent[F, ?]): F[Lock[F]] =
    Semaphore[F](maxShared).map { semaphore =>
      new Lock[F] {
        override def shared: Resource[F, Unit] = semaphore.permit

        override def exclusive: Resource[F, Unit] =
          Resource.makeFull((poll: Poll[F]) => poll(semaphore.acquireN(maxShared)))(_ =>
            semaphore.releaseN(maxShared))

        override def tryShared: Resource[F, Boolean] =
          Resource.make(semaphore.tryAcquire) {
            case true => semaphore.release
            case false => F.unit
          }

        override def tryExclusive: Resource[F, Boolean] =
          Resource.make(semaphore.tryAcquireN(maxShared)) {
            case true => semaphore.releaseN(maxShared)
            case false => F.unit
          }
      }
    }

  def apply[F[_]](implicit F: GenConcurrent[F, ?]): F[Lock[F]] =
    apply(IdentityProvider.unique)

  /**
   * <p>A cancellation-safe [[Lock]] implementation that is reentrancy-ready and
   * writer-preferential.
   *
   * <p>This variant uses a user-supplied identity `K` (with [[Eq]]) to enable reentrant
   * behavior: repeated acquisitions using the same identity will succeed without blocking.
   *
   * <p>'''Note''': true fiber-based reentrancy is only possible in [[IO]] via [[IOLocal]],
   * where identity can be safely tied to a [[Fiber]].
   */

  def apply[F[_], K: Eq](identityProvider: IdentityProvider[F, K])(
      implicit F: GenConcurrent[F, ?]): F[Lock[F]] = {
    Ref[F].of(State(None, Q.empty[Claim[F, K]], Q.empty[Claim[F, K]])).map { ref =>
      new Lock[F] {
        override def shared: Resource[F, Unit] =
          Claim.resource(identityProvider).flatMap { claim =>
            def acquire: F[Unit] = F.uncancelable { poll =>
              ref
                .modify {
                  case State(None, _, _) =>
                    State(Current.Shared(Q(claim)).some, Q.empty, Q.empty) -> Right(())

                  // Reentrant access
                  case s @ State(Some(Current.Shared(running)), _, _)
                      if running.exists(_.identity === claim.identity) =>
                    s -> Right(())

                  // Reentrant access
                  case s @ State(Some(Current.Exclusive(running)), _, _)
                      if running.identity === claim.identity =>
                    s -> Right(())

                  case State(Some(Current.Shared(running)), exclQueue, _)
                      if exclQueue.isEmpty =>
                    State(
                      Current.Shared(running.enqueue(claim)).some,
                      Q.empty,
                      Q.empty) -> Right(())

                  case State(Some(Current.Shared(running)), exclQueue, shrdQueue) =>
                    State(
                      Current.Shared(running).some,
                      exclQueue,
                      shrdQueue.enqueue(claim)) -> Left(claim)

                  case State(Some(Current.Exclusive(running)), exclQueue, shrdQueue) =>
                    State(
                      Current.Exclusive(running).some,
                      exclQueue,
                      shrdQueue.enqueue(claim)) -> Left(claim)
                }
                .flatMap {
                  case Right(_) => F.unit
                  case Left(_) =>
                    poll(claim.await).onCancel {
                      ref.update {
                        case State(curr, exclQueue, shrdQueue) =>
                          State(
                            curr,
                            exclQueue,
                            shrdQueue.filterNot(_.identity === claim.identity))
                      }
                    }
                }
            }

            Resource.make(acquire)(_ => unlockShrd(ref, claim.identity))
          }

        override def exclusive: Resource[F, Unit] = {
          Claim.resource(identityProvider).flatMap { claim =>
            def acquire: F[Unit] = F.uncancelable { poll =>
              ref
                .modify {
                  case State(None, _, _) =>
                    State(Current.Exclusive(claim).some, Q.empty, Q.empty) -> Right(())

                  // Reentrant access
                  case state @ State(Some(Current.Exclusive(running)), _, _)
                      if running.identity === claim.identity =>
                    state -> Right(())

                  case State(Some(Current.Exclusive(running)), exclQueue, shrdQueue) =>
                    State(
                      Some(Current.Exclusive(running)),
                      exclQueue.enqueue(claim),
                      shrdQueue) -> Left(claim)

                  case State(Some(Current.Shared(running)), exclQueue, shrdQueue) =>
                    State(
                      Some(Current.Shared(running)),
                      exclQueue.enqueue(claim),
                      shrdQueue) -> Left(claim)
                }
                .flatMap {
                  case Right(_) => F.unit
                  case Left(_) =>
                    poll(claim.await).onCancel {
                      ref.update {
                        case State(curr, exclQueue, shrdQueue) =>
                          State(
                            curr,
                            exclQueue.filterNot(_.identity === claim.identity),
                            shrdQueue)
                      }
                    }
                }
            }

            Resource.make(acquire)(_ => unlockExcl(ref, claim.identity))
          }
        }

        override def tryShared: Resource[F, Boolean] =
          Claim.resource(identityProvider).flatMap { claim =>
            def acquire: F[Boolean] = F.uncancelable { _ =>
              ref.modify {
                case State(None, _, _) =>
                  State(Current.Shared(Q(claim)).some, Q.empty, Q.empty) -> true

                // Reentrant access: already holds shared or exclusive lock
                case s @ State(Some(Current.Shared(running)), _, _)
                    if running.exists(_.identity === claim.identity) =>
                  s -> true

                case s @ State(Some(Current.Exclusive(running)), _, _)
                    if running.identity === claim.identity =>
                  s -> true

                case State(Some(Current.Shared(running)), exclQueue, _) if exclQueue.isEmpty =>
                  State(Current.Shared(running.enqueue(claim)).some, Q.empty, Q.empty) -> true

                case State(Some(Current.Shared(running)), exclQueue, shrdQueue) =>
                  State(
                    Current.Shared(running).some,
                    exclQueue,
                    shrdQueue.enqueue(claim)) -> false

                case State(Some(Current.Exclusive(running)), exclQueue, shrdQueue) =>
                  State(
                    Current.Exclusive(running).some,
                    exclQueue,
                    shrdQueue.enqueue(claim)) -> false
              }
            }

            Resource.make(acquire) {
              case true => unlockShrd(ref, claim.identity)
              case false => F.unit
            }
          }

        override def tryExclusive: Resource[F, Boolean] =
          Claim.resource(identityProvider).flatMap { claim =>
            def acquire: F[Boolean] = F.uncancelable { _ =>
              ref.modify {
                case State(None, _, _) =>
                  State(Current.Exclusive(claim).some, Q.empty, Q.empty) -> true

                case state @ State(Some(Current.Exclusive(running)), _, _)
                    if running.identity === claim.identity =>
                  state -> true

                case State(Some(Current.Exclusive(running)), exclQueue, shrdQueue) =>
                  State(
                    Current.Exclusive(running).some,
                    exclQueue.enqueue(claim),
                    shrdQueue) -> false

                case State(Some(Current.Shared(running)), exclQueue, shrdQueue) =>
                  State(
                    Current.Shared(running).some,
                    exclQueue.enqueue(claim),
                    shrdQueue) -> false
              }
            }

            Resource.make(acquire) {
              case true => unlockExcl(ref, claim.identity)
              case false => F.unit
            }
          }
      }
    }
  }

  private def unlockShrd[F[_], K: Eq](ref: Ref[F, State[F, K]], identity: K)(
      implicit F: GenConcurrent[F, ?]): F[Unit] =
    ref.modify {
      case State(Some(Current.Shared(running)), exclQueue, shrdQueue)
          if running.nonEmpty && running.head.identity === identity =>
        val (_, remaining) = running.dequeue

        if (remaining.nonEmpty) {
          State(Some(Current.Shared(remaining)), exclQueue, shrdQueue) -> F.unit
        } else {
          exclQueue.dequeueOption match {
            case Some((next, rest)) =>
              State(Some(Current.Exclusive(next)), rest, shrdQueue) -> next.complete.void

            case None if shrdQueue.nonEmpty =>
              State(Some(Current.Shared(shrdQueue)), Q.empty, Q.empty) ->
                shrdQueue.toList.traverse_(_.complete)

            case None =>
              State(None, Q.empty[Claim[F, K]], Q.empty[Claim[F, K]]) -> F.unit
          }
        }
      case state => state -> F.unit
    }.flatten

  private def unlockExcl[F[_], K: Eq](ref: Ref[F, State[F, K]], identity: K)(
      implicit F: GenConcurrent[F, ?]): F[Unit] = {
    ref.modify {
      case State(Some(Current.Exclusive(current)), exclQueue, shrdQueue)
          if current.identity === identity =>
        exclQueue.dequeueOption match {
          case Some((next, rest)) =>
            State(Some(Current.Exclusive(next)), rest, shrdQueue) -> next.complete.void

          case None if shrdQueue.nonEmpty =>
            State(Some(Current.Shared(shrdQueue)), Q.empty, Q.empty) ->
              shrdQueue.toList.traverse_(_.complete)

          case None =>
            State(None, Q.empty[Claim[F, K]], Q.empty[Claim[F, K]]) -> F.unit
        }

      case state => state -> F.unit
    }.flatten
  }

  private case class Claim[F[_], K](gate: Deferred[F, Unit], identity: K) {
    def await: F[Unit] = gate.get
    def complete: F[Boolean] = gate.complete(())
  }
  private object Claim {
    def resource[F[_], K](identityProvider: IdentityProvider[F, K])(
        implicit F: GenConcurrent[F, ?]): Resource[F, Claim[F, K]] =
      Resource.eval(identityProvider.next.flatMap(Claim[F, K](_)))

    def apply[F[_], K](identity: => K)(implicit F: GenConcurrent[F, ?]): F[Claim[F, K]] =
      Deferred[F, Unit].map(Claim(_, identity))
  }

  trait IdentityProvider[F[_], K] {
    def next: F[K]
  }

  object IdentityProvider {

    def constant[F[_]: Applicative, K](k: K): IdentityProvider[F, K] =
      new IdentityProvider[F, K] {
        def next: F[K] = k.pure[F]
      }

    def fromFunction[F[_]: Sync, K](fn: () => K): IdentityProvider[F, K] =
      new IdentityProvider[F, K] {
        def next: F[K] = Sync[F].delay(fn())
      }

    def fromEffect[F[_], K](fk: F[K]): IdentityProvider[F, K] =
      new IdentityProvider[F, K] {
        def next: F[K] = fk
      }

    def unique[F[_]: Unique]: IdentityProvider[F, Unique.Token] =
      new IdentityProvider[F, Unique.Token] {
        def next: F[Unique.Token] = Unique[F].unique
      }
  }

  private sealed trait Current[F[_], K]
  private object Current {
    case class Shared[F[_], K](running: Q[Claim[F, K]]) extends Current[F, K]
    case class Exclusive[F[_], K](running: Claim[F, K]) extends Current[F, K]
  }

  private case class State[F[_], K](
      current: Option[Current[F, K]],
      exclQueue: Q[Claim[F, K]],
      shrdQueue: Q[Claim[F, K]])
}
