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

import cats.effect.kernel.{Async, Fiber, Resource}
import cats.effect.kernel.implicits._
import cats.syntax.all._

import scala.annotation.tailrec
import scala.collection.immutable.LongMap
import scala.concurrent.{Future, Promise}

import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}

sealed trait Dispatcher[F[_]] extends DispatcherPlatform[F] {

  def unsafeToFutureCancelable[A](fa: F[A]): (Future[A], () => Future[Unit])

  def unsafeToFuture[A](fa: F[A]): Future[A] =
    unsafeToFutureCancelable(fa)._1

  def unsafeRunAndForget[A](fa: F[A]): Unit = {
    unsafeToFutureCancelable(fa)
    ()
  }
}


object Dispatcher {

  private[this] val Open = () => ()

  def apply[F[_]](implicit F: Async[F]): Resource[F, Dispatcher[F]] = {
    final case class Registration(action: F[Unit], prepareCancel: F[Unit] => Unit)

    final case class State(end: Long, registry: LongMap[Registration]) {

      /*
       * We know we never need structural equality on this type. We do, however,
       * perform a compare-and-swap relatively frequently, and that operation
       * delegates to the `equals` implementation. I like having the case class
       * for pattern matching and general convenience, so we simply override the
       * equals/hashCode implementation to use pointer equality.
       */
      override def equals(that: Any) = this eq that.asInstanceOf[AnyRef]
      override def hashCode = System.identityHashCode(this)
    }

    val Empty = State(0, LongMap())

    for {
      latch <- Resource.liftF(F.delay(new AtomicReference[() => Unit]))
      state <- Resource.liftF(F.delay(new AtomicReference[State](Empty)))

      alive <- Resource.make(F.delay(new AtomicBoolean(true)))(ref => F.delay(ref.set(false)))
      active <- Resource.make(F.ref(LongMap[Fiber[F, Throwable, Unit]]())) { active =>
        for {
          fibers <- active.get
          _ <- fibers.values.toList.parTraverse_(_.cancel)
        } yield ()
      }

      dispatcher = for {
        _ <- F.delay(latch.set(null)) // reset to null

        s <- F delay {
          @tailrec
          def loop(): State = {
            val s = state.get()
            if (!state.compareAndSet(s, s.copy(registry = s.registry.empty)))
              loop()
            else
              s
          }

          loop()
        }

        State(end, registry) = s

        _ <-
          if (registry.isEmpty) {
            F.async_[Unit] { cb =>
              if (!latch.compareAndSet(null, () => cb(Right(())))) {
                // state was changed between when we last set the latch and now; complete the callback immediately
                cb(Right(()))
              }
            }
          } else {
            F uncancelable { _ =>
              for {
                // for catching race conditions where we finished before we were in the map
                completed <- F.ref(LongMap[Unit]())

                identifiedFibers <- registry.toList traverse {
                  case (id, Registration(action, prepareCancel)) =>
                    val enriched = action guarantee {
                      completed.update(_.updated(id, ())) *> active.update(_ - id)
                    }

                    for {
                      fiber <- enriched.start
                      _ <- F.delay(prepareCancel(fiber.cancel))
                    } yield (id, fiber)
                }

                _ <- active.update(_ ++ identifiedFibers)

                // some actions ran too fast, so we need to remove their fibers from the map
                winners <- completed.get
                _ <- active.update(_ -- winners.keys)
              } yield ()
            }
          }
      } yield ()

      _ <- dispatcher.foreverM[Unit].background
    } yield {
      new Dispatcher[F] {
        def unsafeToFutureCancelable[E](fe: F[E]): (Future[E], () => Future[Unit]) = {
          val promise = Promise[E]()

          val action = fe
            .flatMap(e => F.delay(promise.success(e)))
            .onError { case t => F.delay(promise.failure(t)) }
            .void

          @volatile
          var cancelToken: () => Future[Unit] = null

          @volatile
          var canceled = false

          def registerCancel(token: F[Unit]): Unit = {
            cancelToken = () => unsafeToFuture(token)

            // double-check to resolve race condition here
            if (canceled) {
              cancelToken()
              ()
            }
          }

          @tailrec
          def enqueue(): Long = {
            val s @ State(end, registry) = state.get()
            val registry2 = registry.updated(end, Registration(action, registerCancel _))

            if (!state.compareAndSet(s, State(end + 1, registry2)))
              enqueue()
            else
              end
          }

          @tailrec
          def dequeue(id: Long): Unit = {
            val s @ State(_, registry) = state.get()
            val registry2 = registry - id

            if (!state.compareAndSet(s, s.copy(registry = registry2))) {
              dequeue(id)
            }
          }

          if (alive.get()) {
            val id = enqueue()

            val f = latch.getAndSet(Open)
            if (f != null) {
              f()
            }

            val cancel = { () =>
              canceled = true
              dequeue(id)

              val token = cancelToken
              if (token != null)
                token()
              else
                Future.unit
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
