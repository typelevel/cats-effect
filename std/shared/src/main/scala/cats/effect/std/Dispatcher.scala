/*
 * Copyright 2020-2022 Typelevel
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

import cats.effect.kernel.{Async, Resource}
import cats.effect.kernel.implicits._
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
    unsafeToFutureCancelable(fa)
    ()
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
    "3.4.0",
    "use parallel or sequential instead; the former corresponds to the current semantics of this method")
  def apply[F[_]: Async]: Resource[F, Dispatcher[F]] = parallel[F]

  /**
   * Create a [[Dispatcher]] that can be used within a resource scope. Once the resource scope
   * exits, all active effects will be canceled, and attempts to submit new effects will throw
   * an exception.
   *
   * This corresponds to a pattern in which a single `Dispatcher` is being used by multiple
   * calling threads simultaneously, with complex (potentially long-running) actions submitted
   * for evaluation. In this mode, order of operation is not in any way guaranteed, and
   * execution of each submitted action has some unavoidable overhead due to the forking of a
   * new fiber for each action. This mode is most appropriate for scenarios in which a single
   * `Dispatcher` is being widely shared across the application, and where sequencing is not
   * assumed.
   *
   * @see
   *   [[sequential]]
   */
  def parallel[F[_]: Async]: Resource[F, Dispatcher[F]] = apply(Mode.Parallel)

  /**
   * Create a [[Dispatcher]] that can be used within a resource scope. Once the resource scope
   * exits, all active effects will be canceled, and attempts to submit new effects will throw
   * an exception.
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
   * @see
   *   [[parallel]]
   */
  def sequential[F[_]: Async]: Resource[F, Dispatcher[F]] = apply(Mode.Sequential)

  private[this] def apply[F[_]](mode: Mode)(
      implicit F: Async[F]): Resource[F, Dispatcher[F]] = {
    final case class Registration(action: F[Unit], prepareCancel: F[Unit] => Unit)
        extends AtomicBoolean(true)

    sealed trait CancelState
    case object CancelInit extends CancelState
    final case class CanceledNoToken(promise: Promise[Unit]) extends CancelState
    final case class CancelToken(cancelToken: () => Future[Unit]) extends CancelState

    for {
      supervisor <- Supervisor[F]

      (workers, fork) = mode match {
        case Mode.Parallel => (Cpus, supervisor.supervise(_: F[Unit]).map(_.cancel))
        case Mode.Sequential => (1, (_: F[Unit]).as(F.unit).handleError(_ => F.unit))
      }

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
      alive <- Resource.make(F.delay(new AtomicBoolean(true)))(ref => F.delay(ref.set(false)))

      _ <- {
        def dispatcher(
            latch: AtomicReference[() => Unit],
            state: Array[AtomicReference[List[Registration]]]): F[Unit] =
          for {
            _ <- F.delay(latch.set(Noop)) // reset latch

            regs <- F delay {
              val buffer = mutable.ListBuffer.empty[Registration]
              var i = 0
              while (i < workers) {
                val st = state(i)
                if (st.get() ne Nil) {
                  val list = st.getAndSet(Nil)
                  buffer ++= list.reverse
                }
                i += 1
              }
              buffer.toList
            }

            _ <-
              if (regs.isEmpty) {
                F.async_[Unit] { cb =>
                  if (!latch.compareAndSet(Noop, () => cb(Completed))) {
                    // state was changed between when we last set the latch and now; complete the callback immediately
                    cb(Completed)
                  }
                }
              } else {
                val runAll = regs traverse_ {
                  case r @ Registration(action, prepareCancel) =>
                    val supervise: F[Unit] =
                      fork(action).flatMap(cancel => F.delay(prepareCancel(cancel)))

                    // Check for task cancelation before executing.
                    if (r.get()) supervise else F.unit
                }

                mode match {
                  case Dispatcher.Mode.Parallel => runAll.uncancelable
                  case Dispatcher.Mode.Sequential => runAll
                }
              }
          } yield ()

        (0 until workers)
          .toList
          .traverse_(n => dispatcher(latches(n), states(n)).foreverM[Unit].background)
      }
    } yield {
      new Dispatcher[F] {

        def unsafeToFutureCancelable[E](fe: F[E]): (Future[E], () => Future[Unit]) = {
          val promise = Promise[E]()

          val action = fe
            .flatMap(e => F.delay(promise.success(e)))
            .onError { case t => F.delay(promise.failure(t)) }
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
