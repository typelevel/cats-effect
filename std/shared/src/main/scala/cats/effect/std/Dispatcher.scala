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

import cats.Applicative
import cats.effect.kernel.{
  Async,
  Concurrent,
  Cont,
  Deferred,
  MonadCancel,
  MonadCancelThrow,
  Outcome,
  Ref,
  Resource,
  Spawn,
  Sync
}
import cats.effect.kernel.syntax.all._
import cats.effect.std.Dispatcher.parasiticEC
import cats.syntax.all._

import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Failure

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

/**
 * A fiber-based supervisor utility for evaluating effects across an impure boundary. This is
 * useful when working with reactive interfaces that produce potentially many values (as opposed
 * to one), and for each value, some effect in `F` must be performed (like inserting each value
 * into a queue).
 *
 * [[Dispatcher]] is a kind of [[Supervisor]] and accordingly follows the same scoping and
 * lifecycle rules with respect to submitted effects.
 *
 * Performance note: all clients of a single [[Dispatcher]] instance will contend with each
 * other when submitting effects. However, [[Dispatcher]] instances are cheap to create and have
 * minimal overhead, so they can be allocated on-demand if necessary.
 *
 * Notably, [[Dispatcher]] replaces Effect and ConcurrentEffect from Cats Effect 2 while only
 * requiring an [[cats.effect.kernel.Async]] constraint.
 */
trait Dispatcher[F[_]] extends DispatcherPlatform[F] {

  /**
   * Submits an effect to be executed, returning a `Future` that holds the result of its
   * evaluation, along with a cancelation token that can be used to cancel the original effect.
   */
  def unsafeToFutureCancelable[A](fa: F[A]): (Future[A], () => Future[Unit])

  /**
   * Submits an effect to be executed, returning a `Future` that holds the result of its
   * evaluation.
   */
  def unsafeToFuture[A](fa: F[A]): Future[A] =
    unsafeToFutureCancelable(fa)._1

  /**
   * Submits an effect to be executed, returning a cancelation token that can be used to cancel
   * it.
   */
  def unsafeRunCancelable[A](fa: F[A]): () => Future[Unit] =
    unsafeToFutureCancelable(fa)._2

  /**
   * Submits an effect to be executed with fire-and-forget semantics.
   */
  def unsafeRunAndForget[A](fa: F[A]): Unit =
    unsafeToFuture(fa).onComplete {
      case Failure(ex) => reportFailure(ex)
      case _ => ()
    }(parasiticEC)

  protected def reportFailure(t: Throwable): Unit =
    t.printStackTrace()

  // package-private because it's just an internal utility which supports specific implementations
  // anyone who needs this type of thing should use unsafeToFuture and then onComplete
  private[std] def unsafeRunAsync[A](fa: F[A])(cb: Either[Throwable, A] => Unit): Unit =
    unsafeToFuture(fa).onComplete(t => cb(t.toEither))(parasiticEC)
}

object Dispatcher {

  private val parasiticEC: ExecutionContext = new ExecutionContext {
    def execute(runnable: Runnable) = runnable.run()

    def reportFailure(t: Throwable) = t.printStackTrace()
  }

  private[this] val Cpus: Int = Runtime.getRuntime().availableProcessors()

  @deprecated(
    message =
      "use '.parallel' or '.sequential' instead; the former corresponds to the current semantics of '.apply'",
    since = "3.4.0")
  def apply[F[_]: Async]: Resource[F, Dispatcher[F]] = parallel[F](await = false)

  /**
   * Create a [[Dispatcher]] that can be used within a resource scope. Once the resource scope
   * exits, all active effects will be canceled, and attempts to submit new effects will throw
   * an exception.
   */
  def parallel[F[_]: Async]: Resource[F, Dispatcher[F]] = parallel(false)

  /**
   * Create a [[Dispatcher]] that can be used within a resource scope. Once the resource scope
   * exits, all active effects will be canceled, and attempts to submit new effects will throw
   * an exception.
   */
  def sequential[F[_]: Async]: Resource[F, Dispatcher[F]] = sequential(false)

  /**
   * Create a [[Dispatcher]] that can be used within a resource scope. Once the resource scope
   * exits, depending on the termination policy all active effects will be canceled or awaited,
   * and attempts to submit new effects will throw an exception.
   *
   * This corresponds to a pattern in which a single `Dispatcher` is being used by multiple
   * calling threads simultaneously, with complex (potentially long-running) actions submitted
   * for evaluation. In this mode, order of operation is not in any way guaranteed, and
   * execution of each submitted action has some unavoidable overhead due to the forking of a
   * new fiber for each action. This mode is most appropriate for scenarios in which a single
   * `Dispatcher` is being widely shared across the application, and where sequencing is not
   * assumed.
   *
   * The lifecycle of spawned fibers is managed by [[Supervisor]]. The termination policy can be
   * configured by the `await` parameter.
   *
   * @see
   *   [[Supervisor]] for the termination policy details
   *
   * @note
   *   if an effect that never completes, is evaluating by a `Dispatcher` with awaiting
   *   termination policy, the termination of the `Dispatcher` is indefinitely suspended
   *   {{{
   *   val io: IO[Unit] = // never completes
   *     Dispatcher.parallel[F](await = true).use { dispatcher =>
   *       dispatcher.unsafeRunAndForget(Concurrent[F].never)
   *       Concurrent[F].unit
   *     }
   *   }}}
   *
   * @param await
   *   the termination policy of the internal [[Supervisor]].
   *   - true - wait for the completion of the active fibers
   *   - false - cancel the active fibers
   */
  def parallel[F[_]: Async](await: Boolean): Resource[F, Dispatcher[F]] =
    impl[F](true, await, true)

  /**
   * Create a [[Dispatcher]] that can be used within a resource scope. Once the resource scope
   * exits, depending on the termination policy all active effects will be canceled or awaited,
   * and attempts to submit new effects will throw an exception.
   *
   * This corresponds to a [[Dispatcher]] mode in which submitted actions are evaluated strictly
   * in sequence (FIFO). In this mode, any actions submitted to
   * [[Dispatcher.unsafeRunAndForget]] are guaranteed to run in exactly the order submitted, and
   * subsequent actions will not start evaluation until previous actions are completed. This
   * avoids a significant amount of overhead associated with the [[Parallel]] mode and allows
   * callers to make assumptions around ordering, but the downside is that long-running actions
   * will starve subsequent actions, and all submitters must contend for a singular coordination
   * resource. Thus, this mode is most appropriate for cases where the actions are relatively
   * trivial (such as [[Queue.offer]]) ''and'' the `Dispatcher` in question is ''not'' shared
   * across multiple producers. To be clear, shared dispatchers in sequential mode will still
   * function correctly, but performance will be suboptimal due to single-point contention.
   *
   * @note
   *   if an effect that never completes, is evaluating by a `Dispatcher` with awaiting
   *   termination policy, the termination of the `Dispatcher` is indefinitely suspended
   *   {{{
   *   val io: IO[Unit] = // never completes
   *     Dispatcher.sequential[IO](await = true).use { dispatcher =>
   *       dispatcher.unsafeRunAndForget(IO.never)
   *       IO.unit
   *     }
   *   }}}
   *
   * @param await
   *   the termination policy.
   *   - true - wait for the completion of the active fiber
   *   - false - cancel the active fiber
   */
  def sequential[F[_]: Async](await: Boolean): Resource[F, Dispatcher[F]] =
    impl[F](false, await, false)

  // TODO decide if we want other people to use this
  private[std] def sequentialCancelable[F[_]: Async](
      await: Boolean): Resource[F, Dispatcher[F]] =
    impl[F](false, await, true)

  /*
   * There are three fundamental modes here: sequential, parallel, and sequential-cancelable. There
   * is very little overlap in semantics between the three apart from the submission side. The whole thing is split up into
   * a submission queue with impure enqueue and cancel functions which is drained by the `Worker` and an
   * internal execution protocol which also involves a queue. The `Worker` encapsulates all of the
   * race conditions and negotiations with impure code, while the `Executor` manages running the
   * tasks with appropriate semantics. In parallel mode, we shard the `Worker`s according to the
   * number of CPUs and select a random queue (in impure code) as a target. This reduces contention
   * at the cost of ordering, which is not guaranteed in parallel mode. With the sequential modes, there
   * is only a single worker.
   *
   * On the impure side, the queue bit is the easy part: it's just a `UnsafeUnbounded` (queue) which
   * accepts Registration(s). It's easiest to think of this a bit like an actor model, where the
   * `Worker` is the actor and the enqueue is the send. Whenever we send a unit of work, that
   * message has an `AtomicReference` which allows us to back-propagate a cancelation action. That
   * cancelation action can be used in impure code by sending it back to us using the Finalizer
   * message. There are certain race conditions involved in canceling work on the queue and work
   * which is in the process of being taken off the queue, and those race conditions are negotiated
   * between the impure code and the `Worker`.
   *
   * On the pure side, the three different `Executor`s are very distinct. In parallel mode, it's easy:
   * we have a separate `Supervisor` which doesn't respawn actions, and we use that supervisor to
   * spawn a new fiber for each task unit. Cancelation in this mode is easy: we just cancel the fiber.
   *
   * Sequential mode is the simplest of all: all work is executed in-place and cannot be canceled.
   * The cancelation action in all cases is simply `unit` because the impure submission will not be
   * seen until after the work is completed *anyway*, so there's no point in being fancy.
   *
   * For sequential-cancelable mode, we spawn a *single* executor fiber on the main supervisor (which respawns).
   * This fiber is paired with a pure unbounded queue and a shutoff latch. New work is placed on the
   * queue, which the fiber takes from in order and executes in-place. If the work self-cancels or
   * errors, the executor will be restarted. In the case of external cancelation, we shut off the
   * latch (to hold new work), drain the entire work queue into a scratch space, then cancel the
   * executor fiber in-place so long as we're sure it's actively working on the target task. Once
   * that cancelation completes (which will ultimately restart the executor fiber), we re-fill the
   * queue and unlock the latch to allow new work (from the `Worker`).
   */
  private[this] def impl[F[_]: Async](
      parallel: Boolean,
      await: Boolean,
      cancelable: Boolean): Resource[F, Dispatcher[F]] = {
    val always = Some((_: Outcome[F, Throwable, _]) => true)

    // the outer supervisor is for the worker fibers
    // the inner supervisor is for tasks (if parallel) and finalizers
    Supervisor[F](await = await, checkRestart = always) flatMap { supervisor =>
      // we only need this flag to raise the IllegalStateException after closure (Supervisor can't do it for us)

      val termination = Resource.make(Sync[F].delay(new AtomicBoolean(false)))(doneR =>
        Sync[F].delay(doneR.set(true)))

      val awaitTermination = Resource.make(Concurrent[F].deferred[Unit])(_.complete(()).void)

      (awaitTermination, termination) flatMapN { (terminationLatch, doneR) =>
        val executorF =
          if (parallel)
            Executor.parallel[F](await)
          else if (cancelable)
            Executor.sequential(supervisor)
          else
            Resource.pure[F, Executor[F]](Executor.inplace[F])

        // note this scopes the executors *outside* the workers, meaning the workers shut down first
        // I think this is what we want, since it avoids enqueue race conditions
        executorF flatMap { executor =>
          val workerF = Worker[F](executor, terminationLatch)
          val workersF =
            if (parallel)
              workerF.replicateA(Cpus).map(_.toArray)
            else
              workerF.map(w => Array(w))

          workersF evalMap { workers =>
            Async[F].executionContext flatMap { ec =>
              val launchAll = 0.until(workers.length).toList traverse_ { i =>
                supervisor.supervise(workers(i).run)
              }

              launchAll.as(new Dispatcher[F] {
                def unsafeToFutureCancelable[A](fa: F[A]): (Future[A], () => Future[Unit]) = {
                  def inner[E](fe: F[E], result: Promise[E], finalizer: Boolean)
                      : () => Future[Unit] = {
                    if (doneR.get()) {
                      throw new IllegalStateException("Dispatcher already closed")
                    }

                    val stateR = new AtomicReference[RegState[F]] // empty for now, see below

                    // forward atomicity guarantees onto promise completion
                    val promisory = MonadCancel[F] uncancelable { poll =>
                      // invalidate the cancel action when we're done
                      val completeState = Sync[F].delay {
                        stateR.getAndSet(RegState.Completed) match {
                          case st: RegState.CancelRequested =>
                            // we already have a cancel, must complete it:
                            st.latch.success(())
                            ()

                          case RegState.Completed =>
                            throw new AssertionError("unexpected Completed state")

                          case _ =>
                            ()
                        }
                      }
                      poll(fe.guarantee(completeState))
                        .redeemWith(
                          e => Sync[F].delay(result.failure(e)),
                          a => Sync[F].delay(result.success(a)))
                        .void
                    }

                    stateR.set(RegState.Unstarted(promisory))

                    val worker =
                      if (parallel)
                        workers(ThreadLocalRandom.current().nextInt(Cpus))
                      else
                        workers(0)

                    if (finalizer) {
                      worker.queue.unsafeOffer(Registration.Finalizer(promisory))

                      // cannot cancel a cancel
                      () => Future.failed(new UnsupportedOperationException)
                    } else {
                      val reg = new Registration.Primary(stateR)
                      worker.queue.unsafeOffer(reg)

                      @tailrec
                      def cancel(): Future[Unit] = {
                        stateR.get() match {
                          case u: RegState.Unstarted[_] =>
                            val latch = Promise[Unit]()
                            if (stateR.compareAndSet(u, RegState.CancelRequested(latch))) {
                              latch.future
                            } else {
                              cancel()
                            }

                          case r: RegState.Running[_] =>
                            val cancel = r.cancel // indirection needed for Scala 2.12

                            val latch = Promise[Unit]()
                            val _ = inner(cancel, latch, true)
                            latch.future

                          case r: RegState.CancelRequested =>
                            r.latch.future

                          case RegState.Completed =>
                            Future.successful(())
                        }
                      }

                      cancel _
                    }
                  }

                  val result = Promise[A]()
                  (result.future, inner(fa, result, false))
                }

                override def reportFailure(t: Throwable): Unit =
                  ec.reportFailure(t)
              })
            }
          }
        }
      }
    }
  }

  private sealed abstract class RegState[+F[_]] extends Product with Serializable

  private object RegState {
    final case class Unstarted[F[_]](action: F[Unit]) extends RegState[F]
    final case class Running[F[_]](cancel: F[Unit]) extends RegState[F]
    final case class CancelRequested(latch: Promise[Unit]) extends RegState[Nothing]
    case object Completed extends RegState[Nothing]
  }

  private sealed abstract class Registration[F[_]]

  private object Registration {
    final class Primary[F[_]](val stateR: AtomicReference[RegState[F]]) extends Registration[F]

    final case class Finalizer[F[_]](action: F[Unit]) extends Registration[F]

    final case class PoisonPill[F[_]]() extends Registration[F]
  }

  private final class Worker[F[_]: Async](
      val queue: UnsafeAsyncQueue[F, Registration[F]],
      supervisor: Supervisor[F],
      executor: Executor[F],
      terminationLatch: Deferred[F, Unit]) {

    private[this] val doneR = new AtomicBoolean(false)

    def run: F[Unit] = {
      val step = queue.take flatMap {
        case reg: Registration.Primary[F] =>
          Sync[F] defer {
            reg.stateR.get() match {
              case u @ RegState.Unstarted(action) =>
                executor(action) { cancelF =>
                  Sync[F] defer {
                    if (reg.stateR.compareAndSet(u, RegState.Running(cancelF))) {
                      Applicative[F].unit
                    } else {
                      reg.stateR.get() match {
                        case cr @ RegState.CancelRequested(latch) =>
                          if (reg.stateR.compareAndSet(cr, RegState.Running(cancelF))) {
                            supervisor
                              .supervise(cancelF.guarantee(Sync[F].delay {
                                latch.success(())
                                ()
                              }))
                              .void
                          } else {
                            reg.stateR.get() match {
                              case RegState.Completed =>
                                Applicative[F].unit
                              case s =>
                                throw new AssertionError(s"e => $s")
                            }
                          }

                        case RegState.Completed =>
                          Applicative[F].unit

                        case s =>
                          throw new AssertionError(s"b => $s")
                      }
                    }
                  }
                }

              case s @ (RegState.Running(_) | RegState.Completed) =>
                throw new AssertionError(s"c => $s")

              case RegState.CancelRequested(latch) =>
                Sync[F].delay(latch.success(())).void
            }
          }

        case Registration.Finalizer(action) =>
          supervisor.supervise(action).void.voidError

        case Registration.PoisonPill() =>
          Sync[F].delay(doneR.set(true))
      }

      // we're poisoned *first* but our supervisor is killed *last*
      // when this happens, we just block on the termination latch to
      // avoid weirdness. there's still a small gap even then, so we
      // toss in a cede to avoid starvation pathologies
      Sync[F].delay(doneR.get()).ifM(terminationLatch.get >> Spawn[F].cede, step >> run)
    }
  }

  private object Worker {

    def apply[F[_]: Async](
        executor: Executor[F],
        terminationLatch: Deferred[F, Unit]): Resource[F, Worker[F]] = {
      // we make a new supervisor just for cancelation actions
      Supervisor[F](false) flatMap { supervisor =>
        val initF = Sync[F].delay(
          new Worker[F](
            new UnsafeAsyncQueue[F, Registration[F]](),
            supervisor,
            executor,
            terminationLatch))

        Resource.make(initF)(w => Sync[F].delay(w.queue.unsafeOffer(Registration.PoisonPill())))
      }
    }
  }

  private abstract class Executor[F[_]] {
    def apply(task: F[Unit])(registerCancel: F[Unit] => F[Unit]): F[Unit]
  }

  private object Executor {

    // default sequential executor (ignores cancelation)
    def inplace[F[_]: Concurrent]: Executor[F] =
      new Executor[F] {
        def apply(task: F[Unit])(registerCancel: F[Unit] => F[Unit]): F[Unit] = {
          Concurrent[F].deferred[Unit].flatMap { d =>
            (registerCancel(d.get) *> task).guarantee(d.complete(()).void)
          }
        }
      }

    // sequential executor which respects cancelation (at the cost of additional overhead); not used
    def sequential[F[_]: Concurrent](supervisor: Supervisor[F]): Resource[F, Executor[F]] = {
      sealed trait TaskState extends Product with Serializable

      object TaskState {
        final case class Ready(task: F[Unit]) extends TaskState
        case object Executing extends TaskState
        final case class Canceling(latch: Deferred[F, Unit]) extends TaskState
        case object Dead extends TaskState
      }

      Resource.eval(Queue.unbounded[F, Ref[F, TaskState]]) flatMap { tasks =>
        // knock it out of the task taking
        val evict = Concurrent[F].ref[TaskState](TaskState.Dead).flatMap(tasks.offer(_))

        Resource.make(Concurrent[F].ref(false))(r => r.set(true) >> evict) evalMap { doneR =>
          Concurrent[F].ref[Option[Deferred[F, Unit]]](None) flatMap { shutoff =>
            val step = tasks.take flatMap { taskR =>
              taskR.getAndSet(TaskState.Executing) flatMap {
                case TaskState.Ready(task) =>
                  task guarantee {
                    taskR.getAndSet(TaskState.Dead) flatMap {
                      // if we finished during cancelation, we need to catch it before it kills us
                      case TaskState.Canceling(latch) => latch.complete(()).void
                      case _ => Applicative[F].unit
                    }
                  }

                // Executing should be impossible
                case TaskState.Executing | TaskState.Canceling(_) | TaskState.Dead =>
                  Applicative[F].unit
              }
            }

            lazy val loop: F[Unit] = doneR.get.ifM(Applicative[F].unit, step >> loop)
            val spawnExecutor = supervisor.supervise(loop)

            spawnExecutor flatMap { fiber =>
              Concurrent[F].ref(fiber) map { fiberR =>
                new Executor[F] {
                  def apply(task: F[Unit])(registerCancel: F[Unit] => F[Unit]): F[Unit] = {
                    Concurrent[F].ref[TaskState](TaskState.Ready(task)) flatMap { taskR =>
                      val cancelF =
                        Concurrent[F].deferred[Unit] flatMap { cancelLatch =>
                          taskR flatModify {
                            case TaskState.Ready(_) | TaskState.Dead =>
                              (TaskState.Dead, Applicative[F].unit)

                            case TaskState.Canceling(cancelLatch) =>
                              (TaskState.Canceling(cancelLatch), cancelLatch.get)

                            case TaskState.Executing =>
                              // we won the race for cancelation and it's already executing
                              val eff = for {
                                // lock the door
                                latch <- Concurrent[F].deferred[Unit]
                                _ <- shutoff.set(Some(latch))

                                // drain the task queue
                                scratch <- tasks.tryTakeN(None)

                                // double check that execution didn't finish while we drained
                                _ <- cancelLatch.tryGet flatMap {
                                  case Some(_) =>
                                    Applicative[F].unit

                                  case None =>
                                    for {
                                      // kill the current executor
                                      _ <- fiberR.get.flatMap(_.cancel)

                                      // restore all of the tasks
                                      _ <- scratch.traverse_(tasks.offer(_))

                                      // start a new fiber
                                      _ <- spawnExecutor.flatMap(fiberR.set(_))

                                      // allow everyone else back in
                                      _ <- latch.complete(())
                                      _ <- shutoff.set(None)
                                    } yield ()
                                }

                                _ <- cancelLatch.complete(())
                              } yield ()

                              (TaskState.Canceling(cancelLatch), eff)
                          }
                        }

                      // in rare cases, this can create mutual ordering issues with quickly enqueued tasks
                      val optBlock = shutoff.get flatMap {
                        case Some(latch) => latch.get
                        case None => Applicative[F].unit
                      }

                      optBlock >> tasks.offer(taskR) >> registerCancel(cancelF)
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    def parallel[F[_]: Concurrent](await: Boolean): Resource[F, Executor[F]] =
      Supervisor[F](await = await) map { supervisor =>
        new Executor[F] {
          def apply(task: F[Unit])(registerCancel: F[Unit] => F[Unit]): F[Unit] =
            supervisor.supervise(task).flatMap(fiber => registerCancel(fiber.cancel))
        }
      }
  }

  private val RightUnit: Right[Nothing, Unit] = Right(())

  // MPSC assumption
  private final class UnsafeAsyncQueue[F[_]: Async, A]
      extends AtomicReference[Either[Throwable, Unit] => Unit](null) { latchR =>

    private[this] val buffer = new UnsafeUnbounded[A]()

    def unsafeOffer(a: A): Unit = {
      val _ = buffer.put(a)
      val back = latchR.get()
      if (back ne null) back(RightUnit)
    }

    def take: F[A] = Async[F].cont[Unit, A] {
      new Cont[F, Unit, A] {
        def apply[G[_]: MonadCancelThrow] = { (k, get, lift) =>
          val takeG = lift(Sync[F].delay(buffer.take()))
          val setLatchG = lift(Sync[F].delay(latchR.set(k)))
          val unsetLatchG = lift(Sync[F].delay(latchR.lazySet(null)))

          takeG.handleErrorWith { _ => // emptiness is reported as a FailureSignal error
            setLatchG *> (takeG <* unsetLatchG).handleErrorWith { _ => // double-check
              get *> unsetLatchG *> lift(take) // recurse
            }
          }
        }

      }
    }

  }
}
