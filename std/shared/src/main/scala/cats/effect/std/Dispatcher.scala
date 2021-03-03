/*
 * Copyright 2020-2021 Typelevel
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
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

/**
 * A fiber-based supervisor utility for evaluating effects across an impure
 * boundary. This is useful when working with reactive interfaces that produce
 * potentially many values (as opposed to one), and for each value, some effect
 * in `F` must be performed (like inserting it into a queue).
 *
 * [[Dispatcher]] is a kind of [[Supervisor]] and accordingly follows the same
 * scoping and lifecycle rules with respect to submitted effects.
 *
 * Performance note: all clients of a single [[Dispatcher]] instance will
 * contend with each other when submitting effects. However, [[Dispatcher]]
 * instances are cheap to create and have minimal overhead (a single fiber),
 * so they can be allocated on-demand if necessary.
 *
 * Notably, [[Dispatcher]] replaces Effect and ConcurrentEffect from Cats
 * Effect 2 while only a requiring an [[cats.effect.kernel.Async]] constraint.
 */
trait Dispatcher[F[_]] extends DispatcherPlatform[F] {

  /**
   * Submits an effect to be executed, returning a `Future` that holds the
   * result of its evaluation, along with a cancellation token that can be
   * used to cancel the original effect.
   */
  def unsafeToFutureCancelable[A](fa: F[A]): (Future[A], () => Future[Unit])

  /**
   * Submits an effect to be executed, returning a `Future` that holds the
   * result of its evaluation.
   */
  def unsafeToFuture[A](fa: F[A]): Future[A] =
    unsafeToFutureCancelable(fa)._1

  /**
   * Submits an effect to be executed, returning a cancellation token that
   * can be used to cancel it.
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
}

object Dispatcher {

  private[this] val Cpus: Int = Runtime.getRuntime().availableProcessors()

  private[this] val Noop: () => Unit = () => ()
  private[this] val Open: () => Unit = () => ()

  private[this] val Completed: Either[Throwable, Unit] = Right(())

  /**
   * Create a [[Dispatcher]] that can be used within a resource scope.
   * Once the resource scope exits, all active effects will be canceled, and
   * attempts to submit new effects will throw an exception.
   */
  def apply[F[_]](implicit F: Async[F]): Resource[F, Dispatcher[F]] = {
    final case class Registration(action: F[Unit], prepareCancel: F[Unit] => Unit)
        extends AtomicBoolean(true)

    sealed trait CancelState
    case object CancelInit extends CancelState
    final case class CanceledNoToken(promise: Promise[Unit]) extends CancelState
    final case class CancelToken(cancelToken: () => Future[Unit]) extends CancelState

    for {
      supervisor <- Supervisor[F]
      latch <- Resource.eval(F delay {
        val latch = new Array[AtomicReference[() => Unit]](Cpus)
        var i = 0
        while (i < Cpus) {
          latch(i) = new AtomicReference(Noop)
          i += 1
        }
        latch
      })
      state <- Resource.eval(F delay {
        val state = new Array[AtomicReference[List[Registration]]](Cpus)
        var i = 0
        while (i < Cpus) {
          state(i) = new AtomicReference(Nil)
          i += 1
        }
        state
      })
      ec <- Resource.eval(F.executionContext)
      alive <- Resource.make(F.delay(new AtomicBoolean(true)))(ref => F.delay(ref.set(false)))

      _ <- {
        def dispatcher(idx: Int): F[Unit] =
          for {
            _ <- F.delay(latch(idx).set(Noop)) // reset latch

            regs <- F delay {
              val list = state(idx).getAndSet(Nil)
              list.reverse
            }

            _ <-
              if (regs.isEmpty) {
                F.async_[Unit] { cb =>
                  if (!latch(idx).compareAndSet(Noop, () => cb(Completed))) {
                    // state was changed between when we last set the latch and now; complete the callback immediately
                    cb(Completed)
                  }
                }
              } else {
                regs.traverse_ {
                  case r @ Registration(action, prepareCancel) =>
                    def supervise: F[Unit] =
                      supervisor
                        .supervise(action)
                        .flatMap(f => F.delay(prepareCancel(f.cancel)))

                    // Check for task cancelation before executing.
                    if (r.get()) supervise else F.unit
                }.uncancelable
              }
          } yield ()

        (0 until Cpus).toList.traverse_(n => dispatcher(n).foreverM[Unit].background)
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
          def enqueue(idx: Int, reg: Registration): Unit = {
            val st = state(idx)
            val curr = st.get()
            val next = reg :: curr

            if (!st.compareAndSet(curr, next)) enqueue(idx, reg)
          }

          if (alive.get()) {
            val reg = Registration(action, registerCancel _)
            val idx = ThreadLocalRandom.current().nextInt(Cpus)
            enqueue(idx, reg)

            val lt = latch(idx)
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
}
