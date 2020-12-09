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

trait Supervisor[F[_], E] {
  def supervise[A](fa: F[A]): F[F[Outcome[F, E, A]]]
}

object Supervisor {
  def apply[F[_], E](implicit F: GenConcurrent[F, E]): Resource[F, Supervisor[F, E]] = {
    final case class State(unblock: Deferred[F, Unit], registrations: List[Registration[_]])
    final case class Registration[A](token: Long, action: F[A], join: Deferred[F, F[Outcome[F, E, A]]])

    def newState: F[State] =
      F.deferred[Unit].map { unblock => State(unblock, List()) }

    for {
      initial <- Resource.liftF(newState)
      // TODO: Queue may be more appropriate but need to add takeAll that blocks on empty
      stateRef <- Resource.liftF(F.ref[State](initial))
      counterRef <- Resource.liftF(F.ref[Long](0))
      activeRef <- Resource.make(F.ref(LongMap[Fiber[F, E, _]]())) { ref =>
        ref.get.flatMap { fibers => fibers.values.toList.parTraverse_(_.cancel) }
      }
      aliveRef <- Resource.make(F.ref(true)) { ref =>
        for {
          _ <- ref.set(false)
          state <- stateRef.get
          // report canceled back for effects that weren't started
          _ <- state.registrations.parTraverse_ {
            case reg => reg.join.complete(F.pure(Outcome.canceled))
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
              started <- state.registrations.traverse[F, (Long, Fiber[F, E, _])] {
                case reg: Registration[i] =>
                  for {
                    fiber <- reg.action.guarantee(completedRef.update(_ + reg.token) >> activeRef.update(_.removed(reg.token))).start
                    _ <- reg.join.complete(fiber.join)
                  } yield (reg.token -> fiber)
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
      new Supervisor[F, E] {
        override def supervise[A](fa: F[A]): F[F[Outcome[F, E, A]]] =
          for {
            alive <- aliveRef.get
            _ <- if (alive) {
              F.unit
            } else {
              // TODO: Should we just Async?
              // F.raiseError(???)
              throw new IllegalStateException("supervisor has shutdown")
            }

            token <- counterRef.updateAndGet(_ + 1)
            joinDef <- Deferred[F, F[Outcome[F, E, A]]]
            reg = Registration(token, fa, joinDef)

            _ <- stateRef.modify {
              case state @ State(unblock, regs) =>
                (state.copy(registrations = reg :: regs), unblock.complete(()).void)
            }.flatten
            join <- joinDef.get
          } yield join
      }
    }
  }
}
