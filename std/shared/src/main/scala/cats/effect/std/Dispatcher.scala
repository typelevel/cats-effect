/*
 * Copyright 2020-2023 Typelevel
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
import cats.effect.kernel.{Async, Concurrent, Deferred, MonadCancel, Outcome, Ref, Resource, Spawn, Sync}
import cats.effect.kernel.syntax.all._
import cats.effect.std.Dispatcher.parasiticEC
import cats.syntax.all._

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.Failure

import java.util.concurrent.{LinkedBlockingQueue, ThreadLocalRandom}
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
  def parallel[F[_]: Async](await: Boolean): Resource[F, Dispatcher[F]] = impl[F](true, await)

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
    impl[F](false, await)

  /*
   * There are two fundamental modes here: sequential and parallel. There is very little overlap
   * in semantics between the two apart from the submission side. The whole thing is split up into
   * a submission queue with impure enqueue and cancel functions which is drained by the `Worker` and an
   * internal execution protocol which also involves a queue. The `Worker` encapsulates all of the
   * race conditions and negotiations with impure code, while the `Executor` manages running the
   * tasks with appropriate semantics. In parallel mode, we shard the `Worker`s according to the
   * number of CPUs and select a random queue (in impure code) as a target. This reduces contention
   * at the cost of ordering, which is not guaranteed in parallel mode. With sequential mode, there
   * is only a single worker.
   *
   * On the impure side, the queue bit is the easy part: it's just a `LinkedBlockingQueue` which
   * accepts Registration(s). It's easiest to think of this a bit like an actor model, where the
   * `Worker` is the actor and the enqueue is the send. Whenever we send a unit of work, that
   * message has an `AtomicReference` which allows us to back-propagate a cancelation action. That
   * cancelation action can be used in impure code by sending it back to us using the Finalizer
   * message. There are certain race conditions involved in canceling work on the queue and work
   * which is in the process of being taken off the queue, and those race conditions are negotiated
   * between the impure code and the `Worker`.
   *
   * On the pure side, the two different `Executor`s are very distinct. In parallel mode, it's easy:
   * we have a separate `Supervisor` which doesn't respawn actions, and we use that supervisor to
   * spawn a new fiber for each task unit. Cancelation in this mode is easy: we just cancel the fiber.
   * For sequential mode, we spawn a *single* executor fiber on the main supervisor (which respawns).
   * This fiber is paired with a pure unbounded queue and a shutoff latch. New work is placed on the
   * queue, which the fiber takes from in order and executes in-place. If the work self-cancels or
   * errors, the executor will be restarted. In the case of external cancelation, we shut off the
   * latch (to hold new work), drain the entire work queue into a scratch space, then cancel the
   * executor fiber in-place so long as we're sure it's actively working on the target task. Once
   * that cancelation completes (which will ultimately restart the executor fiber), we re-fill the
   * queue and unlock the latch to allow new work (from the `Worker`).
   *
   * Note that a third mode is possible but not implemented: sequential *without* cancelation. In
   * this mode, we execute each task directly on the worker fiber as it comes in, without the
   * added indirection of the executor queue. This reduces overhead considerably, but the price is
   * we can no longer support external (impure) cancelation. This is because the worker fiber is
   * *also* responsible for dequeueing from the impure queue, which is where the cancelation tasks
   * come in. The worker can't observe a cancelation action while it's executing another action, so
   * cancelation cannot then preempt and is effectively worthless.
   */
  private[this] def impl[F[_]: Async](
      parallel: Boolean,
      await: Boolean): Resource[F, Dispatcher[F]] =
    // the outer supervisor is for the worker fibers
    // the inner supervisor is for tasks (if parallel) and finalizers
    Supervisor[F](
      await = await,
      checkRestart = Some((_: Outcome[F, Throwable, _]) => true)) flatMap { supervisor =>
        // we only need this flag to raise the IllegalStateException after closure (Supervisor can't do it for us)
        val termination = Resource.make(Sync[F].delay(new AtomicBoolean(false)))(doneR =>
          Sync[F].delay(doneR.set(true)))

        termination flatMap { doneR =>
          val executorF: Resource[F, Executor[F]] = if (parallel)
            Executor.parallel[F](await)
          else
            Resource.pure(Executor.inplace[F])

          // note this scopes the executors *outside* the workers, meaning the workers shut down first
          // I think this is what we want, since it avoids enqueue race conditions
          executorF flatMap { executor =>
            val workerF = Worker[F](executor)
            val workersF =
              if (parallel)
                workerF.replicateA(Cpus).map(_.toArray)
              else
                workerF.map(w => Array(w))

            workersF evalMap { workers =>
              Async[F].executionContext flatMap { ec =>
                val launchAll = 0.until(workers.length).toList traverse_ { i =>
                  supervisor.supervise(workers(i).run).void
                }

                launchAll.as(new Dispatcher[F] {
                  def unsafeToFutureCancelable[A](fa: F[A]): (Future[A], () => Future[Unit]) = {
                    def inner[E](fe: F[E], checker: Option[F[Boolean]])
                        : (Future[E], () => Future[Unit]) = {
                      if (doneR.get()) {
                        throw new IllegalStateException("Dispatcher already closed")
                      }

                      val p = Promise[E]()
                      val cancelR = new AtomicReference[F[Unit]]()
                      val invalidateCancel = Sync[F].delay(cancelR.set(null.asInstanceOf[F[Unit]]))

                      // forward atomicity guarantees onto promise completion
                      val promisory = MonadCancel[F] uncancelable { poll =>
                        // invalidate the cancel action when we're done
                        poll(fe.guarantee(invalidateCancel)).redeemWith(
                          e => Sync[F].delay(p.failure(e)),
                          a => Sync[F].delay(p.success(a)))
                      }

                      val worker =
                        if (parallel)
                          workers(ThreadLocalRandom.current().nextInt(Cpus))
                        else
                          workers(0)

                      checker match {
                        // fe is a finalizer
                        case Some(check) =>
                          // bypass over-eager warning
                          // can get rid of this if we GADT encode `inner`'s parameters
                          val abortResult: Any = ()
                          val abort = Sync[F].delay(p.success(abortResult.asInstanceOf[E])).void

                          worker
                            .queue
                            .unsafeOffer(Registration.Finalizer(promisory.void, check, abort))

                          // cannot cancel a cancel
                          (p.future, () => Future.failed(new UnsupportedOperationException))

                        case None =>
                          val reg = new Registration.Primary(promisory.void, cancelR)
                          worker.queue.unsafeOffer(reg)

                          def cancel(): Future[Unit] = {
                            reg.action = null.asInstanceOf[F[Unit]]

                            val cancelF = cancelR.get()
                            if (cancelF != null) {
                              // cannot use null here
                              if (cancelR.compareAndSet(cancelF, Applicative[F].unit)) {
                                // this looping is fine, since we drop the cancel
                                // note that action is published here since the CAS passed
                                inner(cancelF, Some(Sync[F].delay(cancelR.get() != null)))._1
                              } else {
                                Future.successful(())
                              }
                            } else {
                              Future.successful(())
                            }
                          }

                          (p.future, cancel _)
                      }
                    }

                    inner(fa, None)
                  }

                  override def reportFailure(t: Throwable): Unit =
                    ec.reportFailure(t)
                })
              }
            }
          }
        }
    }

  private sealed abstract class Registration[F[_]]

  private object Registration {
    final class Primary[F[_]](var action: F[Unit], val cancelR: AtomicReference[F[Unit]])
        extends Registration[F]

    // the check action verifies that we haven't completed in the interim (as determined by the promise)
    // the abort action runs in the event that we fail that check and need to complete the cancel promise
    final case class Finalizer[F[_]](action: F[Unit], check: F[Boolean], abort: F[Unit])
        extends Registration[F]

    final case class PoisonPill[F[_]]() extends Registration[F]
  }

  // the signal is just a skolem for the atomic references; we never actually run it
  private final class Worker[F[_]: Async](
      val queue: UnsafeAsyncQueue[F, Registration[F]],
      executor: Executor[F]) {

    private[this] val doneR = new AtomicBoolean(false)

    def run: F[Unit] = {
      val step = queue.take flatMap {
        case reg: Registration.Primary[F] =>
          Sync[F].delay(reg.cancelR.get()) flatMap { sig =>
            val action = reg.action

            // double null check catches overly-aggressive memory fencing
            if (sig == null && action != null) {
              executor(action) { cancelF =>
                // we need to double-check that we weren't canceled while spawning
                Sync[F].delay(reg.cancelR.compareAndSet(null.asInstanceOf[F[Unit]], cancelF)) flatMap {
                  case true =>
                    // we weren't canceled!
                    Applicative[F].unit

                  case false =>
                    // we were canceled while spawning, so forward that on to the cancelation action
                    cancelF
                }
              }
            } else {
              // don't spawn, already canceled
              Applicative[F].unit
            }
          }

        case Registration.Finalizer(action, check, abort) =>
          // here's the double-check for late finalization
          check.ifM(action, abort)

        case Registration.PoisonPill() =>
          Sync[F].delay(doneR.set(true))
      }

      Sync[F].delay(doneR.get()).ifM(Spawn[F].cede, step >> run)
    }
  }

  private object Worker {

    def apply[F[_]: Async](executor: Executor[F]): Resource[F, Worker[F]] = {
      val initF = Sync[F].delay(
        new Worker[F](new UnsafeAsyncQueue[F, Registration[F]](), executor))

      Resource.make(initF)(w => Sync[F].delay(w.queue.unsafeOffer(Registration.PoisonPill())))
    }
  }

  private abstract class Executor[F[_]] {
    def apply(task: F[Unit])(registerCancel: F[Unit] => F[Unit]): F[Unit]
  }

  private object Executor {

    def inplace[F[_]]: Executor[F] =
      new Executor[F] {
        def apply(task: F[Unit])(registerCancel: F[Unit] => F[Unit]): F[Unit] = task
      }

    // sequential executor which respects cancelation (at the cost of additional overhead); not used
    def sequential[F[_]: Concurrent](supervisor: Supervisor[F]): Resource[F, Executor[F]] = {
      sealed trait TaskSignal extends Product with Serializable

      object TaskSignal {
        final case class Ready(task: F[Unit]) extends TaskSignal
        case object Executing extends TaskSignal
        case object Void extends TaskSignal
      }

      Resource.eval(Queue.unbounded[F, Ref[F, TaskSignal]]) flatMap { tasks =>
        // knock it out of the task taking
        val evict = Concurrent[F].ref[TaskSignal](TaskSignal.Void).flatMap(tasks.offer(_))

        Resource.make(Concurrent[F].ref(false))(r => r.set(true) >> evict) evalMap { doneR =>
          Concurrent[F].ref[Option[Deferred[F, Unit]]](None) flatMap { shutoff =>
            val step = tasks.take flatMap { taskR =>
              taskR.getAndSet(TaskSignal.Executing) flatMap {
                case TaskSignal.Ready(task) => task.guarantee(taskR.set(TaskSignal.Void))
                // Executing should be impossible
                case TaskSignal.Executing | TaskSignal.Void => Applicative[F].unit
              }
            }

            lazy val loop: F[Unit] = doneR.get.ifM(Applicative[F].unit, step >> loop)
            val spawnExecutor = supervisor.supervise(loop)

            spawnExecutor flatMap { fiber =>
              Concurrent[F].ref(fiber) map { fiberR =>
                new Executor[F] {
                  def apply(task: F[Unit])(registerCancel: F[Unit] => F[Unit]): F[Unit] = {
                    Concurrent[F].ref[TaskSignal](TaskSignal.Ready(task)) flatMap { taskR =>
                      val cancelF = taskR.getAndSet(TaskSignal.Void) flatMap {
                        case TaskSignal.Ready(_) | TaskSignal.Void =>
                          Applicative[F].unit

                        case TaskSignal.Executing =>
                          Concurrent[F].deferred[Unit] flatMap { latch =>
                            // TODO if someone else is already canceling, this will create a deadlock
                            // to fix this deadlock, we need a fourth TaskSignal state and a double-check on that and the shutoff

                            // Step 1: Turn off everything
                            shutoff.set(Some(latch)) >> {
                              // Step 2: Drain the queue
                              tasks.tryTakeN(None) flatMap { scratch =>
                                // Step 3: Cancel the executor, put it all back, and restart executor
                                fiberR.get.flatMap(_.cancel) >>
                                  scratch.traverse_(tasks.offer(_)) >>
                                  spawnExecutor.flatMap(fiberR.set(_)) >>
                                  latch.complete(()) >>
                                  shutoff.set(None)
                              }
                            }
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

  private val Signal: Either[Any, Unit] => Unit = _ => ()
  private val RightUnit: Right[Nothing, Unit] = Right(())

  // MPSC assumption
  private final class UnsafeAsyncQueue[F[_]: Async, A] {
    private[this] val buffer = new LinkedBlockingQueue[A]()
    private[this] val latchR = new AtomicReference[Either[Throwable, Unit] => Unit](null)

    def unsafeOffer(a: A): Unit = {
      buffer.offer(a)

      val f = latchR.get()
      if (latchR.compareAndSet(f, Signal)) {
        if (f != null) {
          f(RightUnit)
        }
        // if f == null, take will loop back around and discover the Signal
      }
    }

    def take: F[A] = {
      val latchF = Async[F].asyncCheckAttempt[Unit] { k =>
        Sync[F] delay {
          if (latchR.compareAndSet(null, k)) {
            Left(Some(Applicative[F].unit)) // all cleanup is elsewhere
          } else {
            val result = latchR.compareAndSet(Signal, null)
            require(
              result
            ) // since this is a single consumer queue, we should never have multiple callbacks
            Right(())
          }
        }
      }

      MonadCancel[F] uncancelable { poll =>
        Sync[F].delay(buffer.poll()) flatMap { a =>
          if (a == null)
            poll(latchF >> take)
          else
            Applicative[F].pure(a)
        }
      }
    }
  }
}
