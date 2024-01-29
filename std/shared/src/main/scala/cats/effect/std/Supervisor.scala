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

import cats.effect.kernel._
import cats.effect.kernel.implicits._
import cats.syntax.all._

import scala.collection.mutable.ListBuffer

import java.util.concurrent.ConcurrentHashMap

/**
 * A fiber-based supervisor that monitors the lifecycle of all fibers that are started via its
 * interface. The lifecycles of all these spawned fibers are bound to the lifecycle of the
 * [[Supervisor]] itself.
 *
 * Whereas [[cats.effect.kernel.GenSpawn.background]] links the lifecycle of the spawned fiber
 * to the calling fiber, starting a fiber via a [[Supervisor]] links the lifecycle of the
 * spawned fiber to the supervisor fiber. This is useful when the scope of some fiber must
 * survive the spawner, but should still be confined within some "larger" scope.
 *
 * The fibers started via the supervisor are guaranteed to be terminated when the supervisor is
 * terminated. When a supervisor is finalized, all active and queued fibers will be safely
 * finalized before finalization of the supervisor is complete.
 *
 * The following diagrams illustrate the lifecycle of a fiber spawned via
 * [[cats.effect.kernel.GenSpawn.start]], [[cats.effect.kernel.GenSpawn.background]], and
 * [[Supervisor]]. In each example, some fiber A is spawning another fiber B. Each box
 * represents the lifecycle of a fiber. If a box is enclosed within another box, it means that
 * the lifecycle of the former is confined within the lifecycle of the latter. In other words,
 * if an outer fiber terminates, the inner fibers are guaranteed to be terminated as well.
 *
 * start:
 * {{{
 * Fiber A lifecycle
 * +---------------------+
 * |                 |   |
 * +-----------------|---+
 *                   |
 *                   |A starts B
 * Fiber B lifecycle |
 * +-----------------|---+
 * |                 +   |
 * +---------------------+
 * }}}
 *
 * background:
 * {{{
 * Fiber A lifecycle
 * +------------------------+
 * |                    |   |
 * | Fiber B lifecycle  |A starts B
 * | +------------------|-+ |
 * | |                  | | |
 * | +--------------------+ |
 * +------------------------+
 * }}}
 *
 * Supervisor:
 * {{{
 * Supervisor lifecycle
 * +---------------------+
 * | Fiber B lifecycle   |
 * | +-----------------+ |
 * | |               + | |
 * | +---------------|-+ |
 * +-----------------|---+
 *                   |
 *                   | A starts B
 * Fiber A lifecycle |
 * +-----------------|---+
 * |                 |   |
 * +---------------------+
 * }}}
 *
 * [[Supervisor]] should be used when fire-and-forget semantics are desired.
 */
trait Supervisor[F[_]] {

  /**
   * Starts the supplied effect `fa` on the supervisor.
   *
   * Trying to start an effect with this method on an already finalized supervisor results in an
   * error (inside `F`).
   *
   * @return
   *   a [[cats.effect.kernel.Fiber]] that represents a handle to the started fiber.
   */
  def supervise[A](fa: F[A]): F[Fiber[F, Throwable, A]]
}

object Supervisor {

  /**
   * Creates a [[cats.effect.kernel.Resource]] scope within which fibers can be monitored. When
   * this scope exits, all supervised fibers will be finalized.
   *
   * @note
   *   if an effect that never completes, is supervised by a `Supervisor` with awaiting
   *   termination policy, the termination of the `Supervisor` is indefinitely suspended
   *   {{{
   *   val io: IO[Unit] = // never completes
   *     Supervisor[IO](await = true).use { supervisor =>
   *       supervisor.supervise(IO.never).void
   *     }
   *   }}}
   *
   * @param await
   *   the termination policy
   *   - true - wait for the completion of the active fibers
   *   - false - cancel the active fibers
   */
  def apply[F[_]](await: Boolean)(implicit F: Concurrent[F]): Resource[F, Supervisor[F]] =
    apply[F](await, None)(F)

  private[std] def apply[F[_]](
      await: Boolean,
      checkRestart: Option[Outcome[F, Throwable, _] => Boolean])(
      implicit F: Concurrent[F]): Resource[F, Supervisor[F]] = {
    F match {
      case asyncF: Async[F] => applyForAsync(await, checkRestart)(asyncF)
      case _ => applyForConcurrent(await, checkRestart)
    }
  }

  def apply[F[_]: Concurrent]: Resource[F, Supervisor[F]] =
    apply[F](false)

  private sealed abstract class State[F[_]] {

    def remove(token: Unique.Token): F[Unit]

    /**
     * Must return `false` (and might not insert) if `Supervisor` is already closed
     */
    def add(token: Unique.Token, fiber: Fiber[F, Throwable, _]): F[Boolean]

    // these are allowed to destroy the state, since they're only called during closing:
    val joinAll: F[Unit]
    val cancelAll: F[Unit]
  }

  private def supervisor[F[_]](
      mkState: Ref[F, Boolean] => F[State[F]], // receives the main shutdown flag
      await: Boolean,
      checkRestart: Option[Outcome[F, Throwable, _] => Boolean])( // `None` never restarts
      implicit F: Concurrent[F]): Resource[F, Supervisor[F]] = {

    for {
      doneR <- Resource.eval(F.ref(false))
      state <- Resource.makeCase(mkState(doneR)) {
        case (st, Resource.ExitCase.Succeeded) if await =>
          doneR.set(true) *> st.joinAll
        case (st, _) =>
          doneR.set(true) *> st.cancelAll
      }
    } yield new Supervisor[F] {

      def supervise[A](fa: F[A]): F[Fiber[F, Throwable, A]] =
        F.uncancelable { _ =>
          val monitor: (F[A], F[Unit]) => F[Fiber[F, Throwable, A]] = checkRestart match {
            case Some(restart) => { (fa, fin) =>
              F.deferred[Outcome[F, Throwable, A]] flatMap { resultR =>
                F.ref(false) flatMap { canceledR =>
                  F.deferred[Fiber[F, Throwable, A]].flatMap { firstCurrent =>
                    // `currentR` holds (a `Deferred` to) the current
                    // incarnation of the fiber executing `fa`:
                    F.ref(firstCurrent).flatMap { currentR =>
                      def action(current: Deferred[F, Fiber[F, Throwable, A]]): F[Unit] = {
                        F uncancelable { _ =>
                          val started = F start {
                            fa guaranteeCase { oc =>
                              F.deferred[Fiber[F, Throwable, A]].flatMap { newCurrent =>
                                // we're replacing the `Deferred` holding
                                // the current fiber with a new one before
                                // the current fiber finishes, and even
                                // before we check for the cancel signal;
                                // this guarantees, that the fiber reachable
                                // through `currentR` is the last one (or
                                // null, see below):
                                currentR.set(newCurrent) *> {
                                  canceledR.get flatMap { canceled =>
                                    doneR.get flatMap { done =>
                                      if (!canceled && !done && restart(oc)) {
                                        action(newCurrent)
                                      } else {
                                        // we must complete `newCurrent`,
                                        // because `cancel` below may wait
                                        // for it; we signal that it is not
                                        // restarted with `null`:
                                        newCurrent.complete(null) *> fin.guarantee(
                                          resultR.complete(oc).void)
                                      }
                                    }
                                  }
                                }
                              }
                            }
                          }

                          started flatMap { f => current.complete(f).void }
                        }
                      }

                      action(firstCurrent).as(
                        new Fiber[F, Throwable, A] {

                          private[this] val delegateF = currentR.get.flatMap(_.get)

                          val cancel: F[Unit] = F uncancelable { _ =>
                            // after setting `canceledR`, at
                            // most one restart happens, and
                            // the fiber we get through `delegateF`
                            // is the final one:
                            canceledR.set(true) *> delegateF flatMap {
                              case null =>
                                // ok, task wasn't restarted, but we
                                // wait for the result to be completed
                                // (and the finalizer to run):
                                resultR.get.void
                              case fiber =>
                                fiber.cancel *> fiber.join flatMap {
                                  case Outcome.Canceled() =>
                                    // cancel successful (or self-canceled),
                                    // but we don't know if the `guaranteeCase`
                                    // above ran so we need to double check:
                                    delegateF.flatMap {
                                      case null =>
                                        // ok, the `guaranteeCase`
                                        // certainly executed/ing:
                                        resultR.get.void
                                      case fiber2 =>
                                        // we cancelled the fiber before it did
                                        // anything, so the finalizer didn't run,
                                        // we need to do it now:
                                        val cleanup = fin.guarantee(
                                          resultR.complete(Outcome.Canceled()).void
                                        )
                                        if (fiber2 eq fiber) {
                                          cleanup
                                        } else {
                                          // this should never happen
                                          cleanup *> F.raiseError(new AssertionError(
                                            "unexpected fiber (this is a bug in Supervisor)"))
                                        }
                                    }
                                  case _ =>
                                    // finished in error/success,
                                    // the outcome will certainly
                                    // be completed:
                                    resultR.get.void
                                }
                            }
                          }

                          def join = resultR.get
                        }
                      )
                    }
                  }
                }
              }
            }

            case None => (fa, fin) => F.start(fa.guarantee(fin))
          }

          for {
            done <- F.ref(false)
            insertResult <- F.deferred[Boolean]
            token <- F.unique
            cleanup = state.remove(token)
            fiber <- monitor(
              // if the supervisor have been (or is now)
              // shutting down, inserting into state will
              // fail; so we need to wait for the positive result
              // of inserting, before actually doing the task:
              insertResult
                .get
                .ifM(
                  fa,
                  F.canceled *> F.raiseError[A](new AssertionError(
                    "supervised fiber couldn't cancel (this is a bug in Supervisor)"))
                ),
              done.set(true) *> cleanup
            )
            insertOk <- state.add(token, fiber)
            _ <- insertResult.complete(insertOk)
            // `cleanup` could run BEFORE the `state.add`
            // (if `fa` is very fast), in which case it doesn't
            // remove the fiber from the state, so we re-check:
            _ <- done.get.ifM(cleanup, F.unit)
            _ <- {
              if (!insertOk) {
                F.raiseError(new IllegalStateException("supervisor already shutdown"))
              } else {
                F.unit
              }
            }
          } yield fiber
        }
    }
  }

  private[effect] def applyForConcurrent[F[_]](
      await: Boolean,
      checkRestart: Option[Outcome[F, Throwable, _] => Boolean])(
      implicit F: Concurrent[F]): Resource[F, Supervisor[F]] = {
    val mkState = F.ref[Map[Unique.Token, Fiber[F, Throwable, _]]](Map.empty).map { stateRef =>
      new State[F] {

        def remove(token: Unique.Token): F[Unit] = stateRef.update {
          case null => null
          case map => map - token
        }

        def add(token: Unique.Token, fiber: Fiber[F, Throwable, _]): F[Boolean] =
          stateRef.modify {
            case null => (null, false)
            case map => (map.updated(token, fiber), true)
          }

        private[this] val allFibers: F[List[Fiber[F, Throwable, _]]] = {
          // we're closing, so we won't need the state any more,
          // so we're using `null` as a sentinel to reject later
          // insertions in `add`:
          stateRef.getAndSet(null).map(_.values.toList)
        }

        val joinAll: F[Unit] = allFibers.flatMap(_.traverse_(_.join.void))

        val cancelAll: F[Unit] = allFibers.flatMap(_.parUnorderedTraverse(_.cancel).void)
      }
    }

    supervisor(_ => mkState, await, checkRestart)
  }

  private[effect] def applyForAsync[F[_]](
      await: Boolean,
      checkRestart: Option[Outcome[F, Throwable, _] => Boolean])(
      implicit F: Async[F]): Resource[F, Supervisor[F]] = {
    def mkState(doneR: Ref[F, Boolean]) = F.delay {
      val state = new ConcurrentHashMap[Unique.Token, Fiber[F, Throwable, _]]
      new State[F] {

        def remove(token: Unique.Token): F[Unit] = F.delay(state.remove(token)).void

        def add(token: Unique.Token, fiber: Fiber[F, Throwable, _]): F[Boolean] = {
          // We might insert a fiber even when closed, but
          // then we return `false`, so it will not actually
          // execute its task, but will self-cancel. In this
          // case we need not remove the (cancelled) fiber
          // from the map, since the whole `Supervisor` is
          // shutting down anyway.
          F.delay(state.put(token, fiber)) *> doneR.get.map(!_)
        }

        private[this] val allFibers: F[List[Fiber[F, Throwable, _]]] =
          F delay {
            val fibers = ListBuffer.empty[Fiber[F, Throwable, _]]
            fibers.sizeHint(state.size())
            val values = state.values().iterator()
            while (values.hasNext) {
              fibers += values.next()
            }

            fibers.result()
          }

        val joinAll: F[Unit] = allFibers.flatMap(_.traverse_(_.join.void))
        val cancelAll: F[Unit] = allFibers.flatMap(_.parUnorderedTraverse(_.cancel).void)
      }
    }

    supervisor(mkState, await, checkRestart)
  }
}
