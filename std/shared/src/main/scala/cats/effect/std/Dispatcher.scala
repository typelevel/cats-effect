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

import cats.effect.kernel.{Async, Deferred, Fiber, MonadCancel, Ref, Resource, Sync}
import cats.effect.kernel.implicits._
import cats.syntax.all._

import scala.annotation.tailrec
import scala.collection.immutable.LongMap
import scala.concurrent.{Future, Promise}

import java.util.concurrent.atomic.AtomicReference

object Dispatcher extends DispatcherPlatform {

  private[this] val Open = () => ()

  def apply[F[_]: Async, A](unsafe: Runner[F] => Resource[F, A]): Resource[F, A] = {
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
      latch <- Resource.liftF(Sync[F].delay(new AtomicReference[() => Unit]))
      state <- Resource.liftF(Sync[F].delay(new AtomicReference[State](Empty)))

      active <- Resource.make(Ref[F].of(Set[Fiber[F, Throwable, Unit]]())) { ref =>
        ref.get.flatMap(_.toList.parTraverse_(_.cancel))
      }

      dispatcher = for {
        _ <- Sync[F].delay(latch.set(null)) // reset to null

        s <- Sync[F] delay {
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
            Async[F].async_[Unit] { cb =>
              if (!latch.compareAndSet(null, () => cb(Right(())))) {
                // state was changed between when we last set the latch and now; complete the callback immediately
                cb(Right(()))
              }
            }
          } else {
            MonadCancel[F] uncancelable { _ =>
              for {
                fibers <- registry.values.toList traverse {
                  case Registration(action, prepareCancel) =>
                    for {
                      fiberDef <- Deferred[F, Fiber[F, Throwable, Unit]]

                      enriched = action guarantee {
                        fiberDef.get.flatMap(fiber => active.update(_ - fiber))
                      }

                      fiber <- enriched.start
                      _ <- fiberDef.complete(fiber)
                      _ <- Sync[F].delay(prepareCancel(fiber.cancel))
                    } yield fiber
                }

                _ <- active.update(_ ++ fibers)
              } yield ()
            }
          }
      } yield ()

      _ <- dispatcher.foreverM[Unit].background

      back <- unsafe {
        new Runner[F] {
          def unsafeToFutureCancelable[E](fe: F[E]): (Future[E], () => Future[Unit]) = {
            val promise = Promise[E]()

            val action = fe
              .flatMap(e => Sync[F].delay(promise.success(e)))
              .onError { case t => Sync[F].delay(promise.failure(t)) }
              .void

            @volatile
            var cancelToken: F[Unit] = null.asInstanceOf[F[Unit]]

            def registerCancel(token: F[Unit]): Unit =
              cancelToken = token

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

            val id = enqueue()

            val f = latch.getAndSet(Open)
            if (f != null) {
              f()
            }

            val cancel = { () =>
              dequeue(id)

              val token = cancelToken
              if (token != null)
                unsafeToFuture(token)
              else
                Future.unit
            }

            (promise.future, cancel)
          }
        }
      }
    } yield back
  }

  sealed trait Runner[F[_]] extends RunnerPlatform[F] {

    def unsafeToFutureCancelable[A](fa: F[A]): (Future[A], () => Future[Unit])

    def unsafeToFuture[A](fa: F[A]): Future[A] =
      unsafeToFutureCancelable(fa)._1

    def unsafeRunAndForget[A](fa: F[A]): Unit = {
      unsafeToFutureCancelable(fa)
      ()
    }
  }
}
