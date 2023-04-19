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

import cats.effect.kernel.{Async, Outcome, Resource}
import cats.syntax.all._

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

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
  def unsafeRunAndForget[A](fa: F[A]): Unit = {
    unsafeRunAsync(fa) {
      case Left(t) => t.printStackTrace()
      case Right(_) => ()
    }
  }

  // package-private because it's just an internal utility which supports specific implementations
  // anyone who needs this type of thing should use unsafeToFuture and then onComplete
  private[std] def unsafeRunAsync[A](fa: F[A])(cb: Either[Throwable, A] => Unit): Unit = {
    // this is safe because the only invocation will be cb
    implicit val parasitic: ExecutionContext = new ExecutionContext {
      def execute(runnable: Runnable) = runnable.run()
      def reportFailure(t: Throwable) = t.printStackTrace()
    }

    unsafeToFuture(fa).onComplete(t => cb(t.toEither))
  }
}

object Dispatcher {

  private[this] val Cpus: Int = Runtime.getRuntime().availableProcessors()

  private[this] val Noop: () => Unit = () => ()
  private[this] val Open: () => Unit = () => ()

  private[this] val Completed: Either[Throwable, Unit] = Right(())

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
  def parallel[F[_]: Async]: Resource[F, Dispatcher[F]] =
    parallel[F](await = false)

  /**
   * Create a [[Dispatcher]] that can be used within a resource scope. Once the resource scope
   * exits, all active effects will be canceled, and attempts to submit new effects will throw
   * an exception.
   */
  def sequential[F[_]: Async]: Resource[F, Dispatcher[F]] =
    sequential[F](await = false)

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
    apply(Mode.Parallel, await)

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
    apply(Mode.Sequential, await)

  private[this] def apply[F[_]](mode: Mode, await: Boolean)(
      implicit F: Async[F]): Resource[F, Dispatcher[F]] = {
    final case class Registration(action: F[Unit], prepareCancel: F[Unit] => Unit)
        extends AtomicBoolean(true)

    sealed trait CancelState
    case object CancelInit extends CancelState
    final case class CanceledNoToken(promise: Promise[Unit]) extends CancelState
    final case class CancelToken(cancelToken: () => Future[Unit]) extends CancelState

    val (workers, makeFork) =
      mode match {
        case Mode.Parallel =>
          // TODO we have to do this for now because Scala 3 doesn't like it (lampepfl/dotty#15546)
          (Cpus, Supervisor[F](await, None).map(s => s.supervise(_: F[Unit]).map(_.cancel)))

        case Mode.Sequential =>
          (
            1,
            Resource
              .pure[F, F[Unit] => F[F[Unit]]]((_: F[Unit]).as(F.unit).handleError(_ => F.unit)))
      }

    for {
      fork <- makeFork

      latches <- Resource.eval(F delay {
        val latches = new Array[AtomicReference[() => Unit]](workers)
        var i = 0
        while (i < workers) {
          latches(i) = new AtomicReference(Noop)
          i += 1
        }
        latches
      })
      states <- Resource.eval(F delay {
        val states = Array.ofDim[AtomicReference[List[Registration]]](workers, workers)
        var i = 0
        while (i < workers) {
          var j = 0
          while (j < workers) {
            states(i)(j) = new AtomicReference(Nil)
            j += 1
          }
          i += 1
        }
        states
      })
      ec <- Resource.eval(F.executionContext)

      // supervisor for the main loop, which needs to always restart unless the Supervisor itself is canceled
      // critically, inner actions can be canceled without impacting the loop itself
      supervisor <- Supervisor[F](await, Some((_: Outcome[F, Throwable, _]) => true))

      _ <- {
        def step(state: Array[AtomicReference[List[Registration]]], await: F[Unit]): F[Unit] =
          for {
            regs <- F delay {
              val buffer = mutable.ListBuffer.empty[Registration]
              var i = 0
              while (i < workers) {
                val st = state(i)
                if (st.get() ne Nil) {
                  val list = st.getAndSet(Nil)
                  buffer ++= list.reverse // FIFO order here is a form of fairness
                }
                i += 1
              }
              buffer.toList
            }

            _ <-
              if (regs.isEmpty) {
                await
              } else {
                regs traverse_ {
                  case r @ Registration(action, prepareCancel) =>
                    val supervise: F[Unit] =
                      fork(action).flatMap(cancel => F.delay(prepareCancel(cancel)))

                    // Check for task cancelation before executing.
                    F.delay(r.get()).ifM(supervise, F.unit)
                }
              }
          } yield ()

        def dispatcher(
            doneR: AtomicBoolean,
            latch: AtomicReference[() => Unit],
            state: Array[AtomicReference[List[Registration]]]): F[Unit] = {

          val await =
            F.async_[Unit] { cb =>
              if (!latch.compareAndSet(Noop, () => cb(Completed))) {
                // state was changed between when we last set the latch and now; complete the callback immediately
                cb(Completed)
              }
            }

          F.delay(latch.set(Noop)) *> // reset latch
            // if we're marked as done, yield immediately to give other fibers a chance to shut us down
            // we might loop on this a few times since we're marked as done before the supervisor is canceled
            F.delay(doneR.get()).ifM(F.cede, step(state, await))
        }

        0.until(workers).toList traverse_ { n =>
          Resource.eval(F.delay(new AtomicBoolean(false))) flatMap { doneR =>
            val latch = latches(n)
            val worker = dispatcher(doneR, latch, states(n))
            val release = F.delay(latch.getAndSet(Open)())
            Resource.make(supervisor.supervise(worker)) { _ =>
              F.delay(doneR.set(true)) *> step(states(n), F.unit) *> release
            }
          }
        }
      }

      // Alive is the innermost resource so that when releasing
      // the very first thing we do is set dispatcher to un-alive
      alive <- Resource.make(F.delay(new AtomicBoolean(true)))(ref => F.delay(ref.set(false)))
    } yield {
      new Dispatcher[F] {
        override def unsafeRunAndForget[A](fa: F[A]): Unit = {
          unsafeRunAsync(fa) {
            case Left(t) => ec.reportFailure(t)
            case Right(_) => ()
          }
        }

        def unsafeToFutureCancelable[E](fe: F[E]): (Future[E], () => Future[Unit]) = {
          val promise = Promise[E]()

          val action = fe
            .flatMap(e => F.delay(promise.success(e)))
            .handleErrorWith(t => F.delay(promise.failure(t)))
            .void

          val cancelState = new AtomicReference[CancelState](CancelInit)

          def registerCancel(token: F[Unit]): Unit = {
            val cancelToken = () => unsafeToFuture(token)

            @tailrec
            def loop(): Unit = {
              val state = cancelState.get()
              state match {
                case CancelInit =>
                  if (!cancelState.compareAndSet(state, CancelToken(cancelToken))) {
                    loop()
                  }
                case CanceledNoToken(promise) =>
                  if (!cancelState.compareAndSet(state, CancelToken(cancelToken))) {
                    loop()
                  } else {
                    cancelToken().onComplete {
                      case Success(_) => promise.success(())
                      case Failure(ex) => promise.failure(ex)
                    }(ec)
                  }
                case _ => ()
              }
            }

            loop()
          }

          @tailrec
          def enqueue(state: AtomicReference[List[Registration]], reg: Registration): Unit = {
            val curr = state.get()
            val next = reg :: curr

            if (!state.compareAndSet(curr, next)) enqueue(state, reg)
          }

          if (alive.get()) {
            val (state, lt) = if (workers > 1) {
              val rand = ThreadLocalRandom.current()
              val dispatcher = rand.nextInt(workers)
              val inner = rand.nextInt(workers)

              (states(dispatcher)(inner), latches(dispatcher))
            } else {
              (states(0)(0), latches(0))
            }

            val reg = Registration(action, registerCancel _)
            enqueue(state, reg)

            if (lt.get() ne Open) {
              val f = lt.getAndSet(Open)
              f()
            }

            val cancel = { () =>
              reg.lazySet(false)

              @tailrec
              def loop(): Future[Unit] = {
                val state = cancelState.get()
                state match {
                  case CancelInit =>
                    val promise = Promise[Unit]()
                    if (!cancelState.compareAndSet(state, CanceledNoToken(promise))) {
                      loop()
                    } else {
                      promise.future
                    }
                  case CanceledNoToken(promise) =>
                    promise.future
                  case CancelToken(cancelToken) =>
                    cancelToken()
                }
              }

              loop()
            }

            // double-check after we already put things in the structure
            if (alive.get()) {
              (promise.future, cancel)
            } else {
              // we were shutdown *during* the enqueue
              cancel()
              throw new IllegalStateException("dispatcher already shutdown")
            }
          } else {
            throw new IllegalStateException("dispatcher already shutdown")
          }
        }
      }
    }
  }

  private sealed trait Mode extends Product with Serializable

  private object Mode {
    case object Parallel extends Mode
    case object Sequential extends Mode
  }
}
