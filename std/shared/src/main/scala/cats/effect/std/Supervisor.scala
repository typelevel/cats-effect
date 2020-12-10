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

package cats.effect.std

import cats.effect.kernel._
import cats.effect.kernel.implicits._
import cats.syntax.all._
import scala.collection.immutable.LongMap

/**
 * A safe, fiber-based supervisor that monitors the lifecycle of all fibers
 * that are started via its interface. The supervisor is managed by a singular
 * fiber which is responsible for starting fibers.
 *
 * Whereas [[GenSpawn.background]] links the lifecycle of the spawned fiber to
 * the calling fiber, starting a fiber via a [[Supervisor]] will link the
 * lifecycle of the spawned fiber to the supervisor fiber. This is useful when
 * the scope of some fiber must survive the spawner, but should still be
 * confined within some scope to prevent leaks.
 *
 * The fibers started via the supervisor are guaranteed to be terminated when
 * the supervisor fiber is terminated. When a supervisor fiber is canceled, all
 * active and queued fibers will be safely finalized before finalization of
 * the supervisor is complete.
 */
trait Supervisor[F[_]] {

  /**
   * Starts the supplied effect `fa` on the supervisor.
   *
   * @return an effect that can be used to wait on the outcome of the fiber,
   * consistent with [[Fiber.join]].
   */
  def supervise[A](fa: F[A]): F[F[Outcome[F, Throwable, A]]]
}

object Supervisor {

  /**
   * Creates a [[Resource]] scope within which fibers can be monitored. When
   * this scope exits, all supervised fibers will be finalized.
   */
  def apply[F[_]](implicit F: Async[F]): Resource[F, Supervisor[F]] = {
    final case class State(unblock: Deferred[F, Unit], registrations: List[Registration[_]])
    final case class Registration[A](
        token: Long,
        action: F[A],
        joinDef: Deferred[F, F[Outcome[F, Throwable, A]]])

    def newState: F[State] =
      F.deferred[Unit].map { unblock => State(unblock, List()) }

    for {
      initial <- Resource.liftF(newState)
      // TODO: Queue may be more appropriate but need to add takeAll that blocks on empty
      stateRef <- Resource.liftF(F.ref[State](initial))
      counterRef <- Resource.liftF(F.ref[Long](0))
      activeRef <- Resource.make(F.ref(LongMap[Fiber[F, Throwable, _]]())) { ref =>
        ref.get.flatMap { fibers => fibers.values.toList.parTraverse_(_.cancel) }
      }
      aliveRef <- Resource.make(F.ref(true)) { ref =>
        for {
          _ <- ref.set(false)
          state <- stateRef.get
          // report canceled back for effects that weren't started
          _ <- state.registrations.parTraverse_ {
            case reg => reg.joinDef.complete(F.pure(Outcome.canceled))
          }
        } yield ()
      }

      supervisor = F.uncancelable { poll =>
        stateRef.get.flatMap { st =>
          if (st.registrations.isEmpty) {
            poll(st.unblock.get)
          } else {
            for {
              nextState <- newState
              state <- stateRef.getAndSet(nextState)

              completedRef <- F.ref[Set[Long]](Set())
              started <- state.registrations.traverse[F, (Long, Fiber[F, Throwable, _])] {
                case reg: Registration[i] =>
                  for {
                    fiber <-
                      reg
                        .action
                        .guarantee(
                          completedRef.update(_ + reg.token) >> activeRef.update(_ - reg.token))
                        .start
                    _ <- reg.joinDef.complete(fiber.join)
                  } yield reg.token -> fiber
              }

              _ <- activeRef.update(_ ++ started)
              completed <- completedRef.get
              _ <- activeRef.update(_ -- completed)
            } yield ()
          }
        }
      }

      _ <- F.background(supervisor.foreverM[Unit])
    } yield {
      new Supervisor[F] {
        override def supervise[A](fa: F[A]): F[F[Outcome[F, Throwable, A]]] =
          for {
            alive <- aliveRef.get
            _ <-
              if (alive) F.unit
              else F.raiseError(new IllegalStateException("supervisor has shutdown"))

            token <- counterRef.updateAndGet(_ + 1)
            joinDef <- Deferred[F, F[Outcome[F, Throwable, A]]]
            reg = Registration(token, fa, joinDef)

            _ <- stateRef.modify {
              case state @ State(unblock, regs) =>
                (state.copy(registrations = reg :: regs), unblock.complete(()).void)
            }.flatten
          } yield joinDef.get.flatten
      }
    }
  }
}
