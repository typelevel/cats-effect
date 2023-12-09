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
import cats.effect.kernel.{Async, Concurrent, MonadCancel, Outcome, Resource, Spawn, Sync}
import cats.effect.std.Dispatcher.parasiticEC
import cats.syntax.all._
import cats.effect.kernel.syntax.all._

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

  private[this] def impl[F[_]: Async](
      parallel: Boolean,
      await: Boolean): Resource[F, Dispatcher[F]] =
    // the outer supervisor is for the worker fibers
    // the inner supervisor is for tasks (if parallel) and finalizers
    Supervisor[F](
      await = await,
      checkRestart = Some((_: Outcome[F, Throwable, _]) => true)) flatMap { workervisor =>
      Supervisor[F](await = await) flatMap { supervisor =>
        // we only need this flag to raise the IllegalStateException after closure (Supervisor can't do it for us)
        val termination = Resource.make(Sync[F].delay(new AtomicBoolean(false)))(doneR =>
          Sync[F].delay(doneR.set(true)))

        termination flatMap { doneR =>
          // we sequentialize on worker spawning, so we don't need to use Deferred here
          Resource.eval(Concurrent[F].ref(Applicative[F].unit)) flatMap { cancelR =>
            def spawn(fu: F[Unit])(setupCancelation: F[Unit] => F[Unit]): F[Unit] = {
              // TODO move this out
              if (parallel)
                // in parallel spawning, we have a real fiber which we need to kill off
                supervisor.supervise(fu).flatMap(f => setupCancelation(f.cancel))
              else
                // in sequential spawning, the only cancelation cancels and restarts the worker itself
                cancelR.get.flatMap(setupCancelation) *> fu
            }

            val workerF = Worker[F](supervisor)(spawn)
            val workersF =
              if (parallel)
                workerF.replicateA(Cpus).map(_.toArray)
              else
                workerF.map(w => Array(w))

            workersF evalMap { workers =>
              Async[F].executionContext flatMap { ec =>
                val launchAll = 0.until(workers.length).toList traverse_ { i =>
                  val launch = workervisor.supervise(workers(i).run)

                  if (parallel)
                    launch.void
                  else
                    launch.flatMap(f => cancelR.set(f.cancel))
                }

                launchAll.as(new Dispatcher[F] {
                  def unsafeToFutureCancelable[A](fa: F[A]): (Future[A], () => Future[Unit]) = {
                    def inner[E](fe: F[E], isFinalizer: Boolean)
                        : (Future[E], () => Future[Unit]) = {
                      if (doneR.get()) {
                        throw new IllegalStateException("Dispatcher already closed")
                      }

                      val p = Promise[E]()
                      val cancelR = new AtomicReference[F[Unit]]()

                      val invalidateCancel =
                        Sync[F].delay(cancelR.set(null.asInstanceOf[F[Unit]]))

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

                      if (isFinalizer) {
                        // bypass over-eager warning
                        val abortResult: Any = ()
                        val abort = Sync[F].delay(p.success(abortResult.asInstanceOf[E])).void

                        worker
                          .queue
                          .unsafeOffer(Registration.Finalizer(promisory.void, cancelR, abort))

                        // cannot cancel a cancel
                        (p.future, () => Future.failed(new UnsupportedOperationException))
                      } else {
                        val reg = new Registration.Primary(promisory.void, cancelR)
                        worker.queue.unsafeOffer(reg)

                        def cancel(): Future[Unit] = {
                          reg.action = null.asInstanceOf[F[Unit]]

                          // publishes action write
                          // TODO this provides incorrect semantics for multiple cancel() call sites
                          val cancelF = cancelR.getAndSet(
                            Applicative[F].unit
                          ) // anything that isn't null, really
                          if (cancelF != null)
                            // this looping is fine, since we drop the cancel
                            inner(cancelF, true)._1
                          else
                            Future.successful(())
                        }

                        (p.future, cancel _)
                      }
                    }

                    inner(fa, false)
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

  private sealed abstract class Registration[F[_]]

  private object Registration {
    final class Primary[F[_]](var action: F[Unit], val cancelR: AtomicReference[F[Unit]])
        extends Registration[F]

    // the only reason for cancelR here is to double check the cancelation invalidation when the
    // action is *observed* by the worker fiber, which only matters in sequential mode
    // this captures the race condition where the cancel action is invoked exactly as the main
    // action completes and avoids the pathological case where we accidentally cancel the worker
    final case class Finalizer[F[_]](
        action: F[Unit],
        cancelR: AtomicReference[F[Unit]],
        abort: F[Unit])
        extends Registration[F]

    final case class PoisonPill[F[_]]() extends Registration[F]
  }

  // the signal is just a skolem for the atomic references; we never actually run it
  private final class Worker[F[_]: Async](
      val queue: UnsafeAsyncQueue[F, Registration[F]],
      supervisor: Supervisor[F])( // only needed for cancelation spawning
      spawn: F[Unit] => (F[Unit] => F[Unit]) => F[Unit]) {

    private[this] val doneR = new AtomicBoolean(false)

    def run: F[Unit] = {
      val step = queue.take flatMap {
        case reg: Registration.Primary[F] =>
          // println("got registration")

          Sync[F].delay(reg.cancelR.get()) flatMap { sig =>
            val action = reg.action

            // double null check catches overly-aggressive memory fencing
            if (sig == null && action != null) {
              // println("...it wasn't canceled")

              spawn(action) { cancelF =>
                // println("...spawned")

                Sync[F].delay(
                  reg.cancelR.compareAndSet(null.asInstanceOf[F[Unit]], cancelF)) flatMap {
                  check =>
                    // we need to double-check that we didn't lose the race
                    if (check)
                      // don't cancel, already spawned
                      Applicative[F].unit
                    else
                      // cancel spawn (we lost the race)
                      cancelF
                }
              }
            } else {
              // don't spawn, already canceled
              Applicative[F].unit
            }
          }

        case Registration.Finalizer(action, cancelR, abort) =>
          // here's the double-check for late finalization
          // if == null then the task is complete and we ignore
          if (cancelR.get() != null)
            supervisor.supervise(action).void
          else
            abort

        case Registration.PoisonPill() =>
          Sync[F].delay(doneR.set(true))
      }

      Sync[F].delay(doneR.get()).ifM(Spawn[F].cede, step >> run)
    }
  }

  private object Worker {

    def apply[F[_]: Async](supervisor: Supervisor[F])(
        spawn: F[Unit] => (F[Unit] => F[Unit]) => F[Unit]): Resource[F, Worker[F]] = {

      val initF = Sync[F].delay(
        new Worker[F](new UnsafeAsyncQueue[F, Registration[F]](), supervisor)(spawn))

      Resource.make(initF)(w => Sync[F].delay(w.queue.unsafeOffer(Registration.PoisonPill())))
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
