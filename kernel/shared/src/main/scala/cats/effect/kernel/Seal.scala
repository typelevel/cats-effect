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

package cats
package effect
package kernel

import cats.syntax.all._

import scala.annotation.tailrec
import scala.collection.immutable.LongMap

import java.util.concurrent.atomic.AtomicReference

/**
 * A purely functional synchronization primitive which represents a one-way
 * single-transition two-state machine
 *
 * When created, a `Seal` is unbroken. It can then be broken exactly once, and is never repaired.
 *
 * `wait` on an unbroken `Seal` will block until the `Seal` completed. `wait` on a
 * broken `Seal` will always immediately return its content.
 *
 * `break` on an unbroken `Seal` will break it, notify any and all readers currently
 * blocked on a call to `wait`, and returns true. `break` on a broken `Seal` 
 * will just return false.
 *
 * Albeit even simpler than `Deferred`, `Seal` can be used in conjunction with [[Ref]] 
 * or `Deferred` to build complex concurrent behaviour and data structures
 *  like queues and semaphores.
 *
 * Finally, the blocking mentioned above is semantic, no actual threads are blocked by the
 * implementation.
 */
abstract class Seal[F[_]] extends SealBreak[F] with SealRead[F] { self =>

  /**
   * Modify the context `F` using transformation `f`.
   */
  def mapK[G[_]](f: F ~> G): Seal[G] = new Seal[G] {
    override def await: G[Unit] = f(self.await)
    override def isBroken: G[Boolean] = f(self.isBroken)
    override def break: G[Boolean] = f(self.break)
  }
}

object Seal {

  /**
   * Creates an unset Seal. Every time you bind the resulting `F`, a new Seal is
   * created. If you want to share one, pass it as an argument and `flatMap` once.
   */
  def apply[F[_]](implicit F: GenConcurrent[F, _]): F[Seal[F]] =
    F.seal

  /**
   * Like `apply` but returns the newly allocated Seal directly instead of wrapping it in
   * `F.delay`. This method is considered unsafe because it is not referentially transparent --
   * it allocates mutable state. In general, you should prefer `apply` and use `flatMap` to get
   * state sharing.
   */
  def unsafe[F[_]: Async]: Seal[F] = new AsyncSeal[F]

  /**
   * Like [[apply]] but initializes state using another effect constructor
   */
  def in[F[_], G[_]](implicit F: Sync[F], G: Async[G]): F[Seal[G]] =
    F.delay(unsafe[G])

  sealed abstract private class State
  private object State {
    case object Broken extends State
    final case class Unbroken(waiting: LongMap[Unit => Unit], nextId: Long) extends State

    val initialId = 1L
    val dummyId = 0L
  }

  final class AsyncSeal[F[_]](implicit F: Async[F]) extends Seal[F] {
    // shared mutable state
    private[this] val ref = new AtomicReference[State](
      State.Unbroken(LongMap.empty, State.initialId)
    )

    def await: F[Unit] = {
      // side-effectful
      def addReader(awakeReader: Unit => Unit): Long = {
        @tailrec
        def loop(): Long =
          ref.get match {
            case State.Broken =>
              awakeReader(())
              State.dummyId // never used
            case s @ State.Unbroken(waiting, nextId) =>
              val updated = State.Unbroken(
                waiting + (nextId -> awakeReader),
                nextId + 1
              )
              if (!ref.compareAndSet(s, updated)) loop()
              else nextId
          }

        loop()
      }

      // side-effectful
      def deleteReader(id: Long): Unit = {
        @tailrec
        def loop(): Unit =
          ref.get match {
            case State.Broken => ()
            case s @ State.Unbroken(waiting, _) =>
              val updated = s.copy(waiting = waiting - id)
              if (!ref.compareAndSet(s, updated)) loop()
              else ()
          }

        loop()
      }

      F.defer {
        ref.get match {
          case State.Broken =>
            F.unit
          case State.Unbroken(_, _) =>
            F.async[Unit] { cb =>
              F.delay(addReader(awakeReader = (_ => cb(Either.unit)))).map { id =>
                // if canceled
                F.delay(deleteReader(id)).some
              }
            }
        }
      }
    }

    def isBroken: F[Boolean] =
      F.delay {
        ref.get match {
          case State.Broken => true
          case State.Unbroken(_, _) => false
        }
      }

    def break: F[Boolean] = {
      def notifyReaders(readers: LongMap[Unit => Unit]): F[Unit] = {
        // LongMap iterators return values in unsigned key order,
        // which corresponds to the arrival order of readers since
        // insertion is governed by a monotonically increasing id
        val cursor = readers.valuesIterator
        var acc = F.unit

        while (cursor.hasNext) {
          val next = cursor.next()
          val task = F.delay(next(()))
          acc = acc >> task
        }

        acc
      }

      // side-effectful (even though it returns F[Unit])
      @tailrec
      def loop(): F[Boolean] =
        ref.get match {
          case State.Broken =>
            F.pure(false)
          case s @ State.Unbroken(readers, _) =>
            val updated = State.Broken
            if (!ref.compareAndSet(s, updated)) loop()
            else {
              val notify = if (readers.isEmpty) F.unit else notifyReaders(readers)
              notify.as(true)
            }
        }

      F.uncancelable(_ => F.defer(loop()))
    }
  }

  def deferred[F[_]](implicit F: GenConcurrent[F, _]): F[Seal[F]] =
    Deferred[F, Unit].map(new DeferredSeal[F](_))

  private class DeferredSeal[F[_]: cats.Functor](defer: Deferred[F, Unit]) extends Seal[F] {
    def await: F[Unit] = defer.get
    def isBroken: F[Boolean] = defer.tryGet.map(_.isDefined)
    def break: F[Boolean] = defer.complete(())
  }

}

trait SealRead[F[_]] extends Serializable {

  /**
   * If this seal is broken, it gets the pure unit value `F`. However, if the
   * `Seal` is not broken yet, then it waits until it is broken. This wait
   * may be canceled.
   */
  def await: F[Unit]

  /**
   * An action that, when performed, indicates if the seal is broken or not. 
   */
  def isBroken: F[Boolean]

}

trait SealBreak[F[_]] extends Serializable {

  /**
   * If this `Seal` is unbroken, breaks it, and notifies all
   * readers currently blocked on a `read`. Returns true.
   *
   * If this `Seal` was already broken, returns false.
   *
   * Satisfies: `Seal[F].flatMap(r => r.break *> r.read) == true.pure[F]`
   */
  def break: F[Boolean]
}
