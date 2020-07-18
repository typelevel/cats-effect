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

package cats.effect
package concurrent

import cats.implicits._
import cats.effect.kernel.{Async, Fiber, Sync}
import cats.effect.concurrent.MVar.TransformedMVar
import cats.~>

import scala.annotation.tailrec

import java.util.concurrent.atomic.AtomicReference

/**
 * @define mVarDescription A mutable location, that is either empty or contains a value of type `A`.
 *
 * It has the following fundamental atomic operations:
 *
 *  - [[put]] which fills the var if empty, or blocks
 *    (asynchronously) until the var is empty again
 *  - [[tryPut]] which fills the var if empty. returns true if successful
 *  - [[take]] which empties the var if full, returning the contained
 *    value, or blocks (asynchronously) otherwise until there is
 *    a value to pull
 *  - [[tryTake]] empties if full, returns None if empty.
 *  - [[read]] which reads the current value without touching it,
 *    assuming there is one, or otherwise it waits until a value
 *    is made available via `put`
 *  - `tryRead` returns a variable if it exists. Implemented in the successor [[MVar]]
 *  - `swap` takes a value, replaces it and returns the taken value. Implemented in the successor [[MVar]]
 *  - [[isEmpty]] returns true if currently empty
 *
 * The `MVar` is appropriate for building synchronization
 * primitives and performing simple inter-thread communications.
 * If it helps, it's similar with a `BlockingQueue(capacity = 1)`,
 * except that it doesn't block any threads, all waiting being
 * done asynchronously (via [[Async]] data types,
 * such as [[IO]]).
 *
 * Given its asynchronous, non-blocking nature, it can be used on
 * top of Javascript as well.
 *
 * Inspired by `Control.Concurrent.MVar` from Haskell and
 * by `scalaz.concurrent.MVar`.
 */
sealed private[concurrent] trait MVarDocumentation extends Any {}

/**
 * $mVarDescription
 */
abstract class MVar[F[_], A] extends MVarDocumentation {

  /**
   * Returns `true` if the `MVar` is empty and can receive a `put`, or
   * `false` otherwise.
   *
   * Note that due to concurrent tasks, logic built in terms of `isEmpty`
   * is problematic.
   */
  def isEmpty: F[Boolean]

  /**
   * Fills the `MVar` if it is empty, or blocks (asynchronously)
   * if the `MVar` is full, until the given value is next in
   * line to be consumed on [[take]].
   *
   * This operation is atomic.
   *
   * @return a task that on evaluation will complete when the
   *         `put` operation succeeds in filling the `MVar`,
   *         with the given value being next in line to
   *         be consumed
   */
  def put(a: A): F[Unit]

  /**
   * Fill the `MVar` if we can do it without blocking,
   *
   * @return whether or not the put succeeded
   */
  def tryPut(a: A): F[Boolean]

  /**
   * Empties the `MVar` if full, returning the contained value,
   * or blocks (asynchronously) until a value is available.
   *
   * This operation is atomic.
   *
   * @return a task that on evaluation will be completed after
   *         a value was retrieved
   */
  def take: F[A]

  /**
   * empty the `MVar` if full
   *
   * @return an Option holding the current value, None means it was empty
   */
  def tryTake: F[Option[A]]

  /**
   * Tries reading the current value, or blocks (asynchronously)
   * until there is a value available.
   *
   * This operation is atomic.
   *
   * @return a task that on evaluation will be completed after
   *         a value has been read
   */
  def read: F[A]

  /**
   * Replaces a value in MVar and returns the old value.
   *
   * @param newValue is a new value
   * @return the value taken
   */
  def swap(newValue: A): F[A]

  /**
   * Returns the value without waiting or modifying.
   *
   * This operation is atomic.
   *
   * @return an Option holding the current value, None means it was empty
   */
  def tryRead: F[Option[A]]

  /**
   * Modify the context `F` using transformation `f`.
   */
  def mapK[G[_]](f: F ~> G): MVar[G, A] =
    new TransformedMVar(this, f)
}

/**
 * Builders for [[MVar]].
 */
object MVar {

  /**
   * Builds an [[MVar]] value for `F` data types that are [[Async]].
   *
   * Due to `Async`'s capabilities, the yielded values by [[MVar.take]]
   * and [[MVar.put]] are cancelable.
   *
   * This builder uses the
   * [[https://typelevel.org/cats/guidelines.html#partially-applied-type-params Partially-Applied Type]]
   * technique.
   *
   * For creating an empty `MVar`:
   * {{{
   *   MVar[IO].empty[Int] <-> MVar.empty[IO, Int]
   * }}}
   *
   * For creating an `MVar` with an initial value:
   * {{{
   *   MVar[IO].init("hello") <-> MVar.init[IO, String]("hello")
   * }}}
   *
   * @see [[of]]and [[empty]]
   */
  def apply[F[_]](implicit F: Async[F]): ApplyBuilders[F] =
    new ApplyBuilders[F](F)

  /**
   * Creates a cancelable `MVar` that starts as empty.
   *
   * @see [[uncancelableEmpty]] for non-cancelable MVars
   *
   * @param F is a [[Concurrent]] constraint, needed in order to
   *        describe cancelable operations
   */
  def empty[F[_], A](implicit F: Async[F]): F[MVar[F, A]] =
    F.delay(MVarAsync.empty)

  /**
   * Creates a cancelable `MVar` that's initialized to an `initial`
   * value.
   *
   * @see [[uncancelableOf]] for non-cancelable MVars
   *
   * @param initial is a value that will be immediately available
   *        for the first `read` or `take` operation
   *
   * @param F is a [[Concurrent]] constraint, needed in order to
   *        describe cancelable operations
   */
  def of[F[_], A](initial: A)(implicit F: Async[F]): F[MVar[F, A]] =
    F.delay(MVarAsync(initial))

  /**
   * Like [[of]] but initializes state using another effect constructor
   */
  def in[F[_], G[_], A](initial: A)(implicit F: Sync[F], G: Async[G]): F[MVar[G, A]] =
    F.delay(MVarAsync(initial))

  /**
   * Like [[empty]] but initializes state using another effect constructor
   */
  def emptyIn[F[_], G[_], A](implicit F: Sync[F], G: Async[G]): F[MVar[G, A]] =
    F.delay(MVarAsync.empty)

  /**
   * Returned by the [[apply]] builder.
   */
  final class ApplyBuilders[F[_]](val F: Async[F]) extends AnyVal {

    /**
     * Builds an `MVar` with an initial value.
     *
     * @see documentation for [[MVar.of]]
     */
    def of[A](a: A): F[MVar[F, A]] =
      MVar.of(a)(F)

    /**
     * Builds an empty `MVar`.
     *
     * @see documentation for [[MVar.empty]]
     */
    def empty[A]: F[MVar[F, A]] =
      MVar.empty(F)
  }

  final private[concurrent] class TransformedMVar[F[_], G[_], A](
      underlying: MVar[F, A],
      trans: F ~> G)
      extends MVar[G, A] {
    override def isEmpty: G[Boolean] = trans(underlying.isEmpty)
    override def put(a: A): G[Unit] = trans(underlying.put(a))
    override def tryPut(a: A): G[Boolean] = trans(underlying.tryPut(a))
    override def take: G[A] = trans(underlying.take)
    override def tryTake: G[Option[A]] = trans(underlying.tryTake)
    override def read: G[A] = trans(underlying.read)
    override def tryRead: G[Option[A]] = trans(underlying.tryRead)
    override def swap(newValue: A): G[A] = trans(underlying.swap(newValue))
  }
}

/**
 * [[MVar]] implementation for [[Async]] data types.
 */
final private[effect] class MVarAsync[F[_], A] private (initial: MVarAsync.State[A])(
    implicit F: Async[F]
) extends MVar[F, A] {
  import MVarAsync._

  /**
   * Shared mutable state.
   */
  private[this] val stateRef = new AtomicReference[State[A]](initial)

  def put(a: A): F[Unit] =
    F.flatMap(tryPut(a)) {
      case true =>
        F.unit // happy path
      case false =>
        F.async(cb => F.defer(unsafePut(a)(cb)).map(Some(_)))
    }

  def tryPut(a: A): F[Boolean] =
    F.defer(unsafeTryPut(a))

  val tryTake: F[Option[A]] =
    F.defer(unsafeTryTake())

  val take: F[A] =
    F.flatMap(tryTake) {
      case Some(a) =>
        F.pure(a) // happy path
      case None =>
        F.async(cb => F.defer(unsafeTake(cb)).map(Some(_)))
    }

  val read: F[A] =
    F.async(cb => F.delay(unsafeRead(cb)).map(Some(_)))

  def tryRead =
    F.delay {
      stateRef.get match {
        case WaitForTake(value, _) => Some(value)
        case WaitForPut(_, _) => None
      }
    }

  def isEmpty: F[Boolean] =
    F.delay {
      stateRef.get match {
        case WaitForPut(_, _) => true
        case WaitForTake(_, _) => false
      }
    }

  def swap(newValue: A): F[A] =
    F.flatMap(take) { oldValue => F.map(put(newValue))(_ => oldValue) }

  @tailrec private def unsafeTryPut(a: A): F[Boolean] =
    stateRef.get match {
      case WaitForTake(_, _) => F.pure(false)

      case current @ WaitForPut(reads, takes) =>
        var first: Listener[A] = null
        val update: State[A] =
          if (takes.isEmpty) State(a)
          else {
            val (x, rest) = takes.dequeue
            first = x
            if (rest.isEmpty) State.empty[A]
            else WaitForPut(LinkedMap.empty, rest)
          }

        if (!stateRef.compareAndSet(current, update)) {
          unsafeTryPut(a) // retry
        } else if ((first ne null) || !reads.isEmpty) {
          streamPutAndReads(a, first, reads)
        } else {
          trueF
        }
    }

  @tailrec private def unsafePut(a: A)(onPut: Listener[Unit]): F[F[Unit]] =
    stateRef.get match {
      case current @ WaitForTake(value, listeners) =>
        val id = new Id
        val newMap = listeners.updated(id, (a, onPut))
        val update = WaitForTake(value, newMap)

        if (stateRef.compareAndSet(current, update)) {
          F.pure(F.delay(unsafeCancelPut(id)))
        } else {
          unsafePut(a)(onPut) // retry
        }

      case current @ WaitForPut(reads, takes) =>
        var first: Listener[A] = null
        val update: State[A] =
          if (takes.isEmpty) State(a)
          else {
            val (x, rest) = takes.dequeue
            first = x
            if (rest.isEmpty) State.empty[A]
            else WaitForPut(LinkedMap.empty, rest)
          }

        if (stateRef.compareAndSet(current, update)) {
          if ((first ne null) || !reads.isEmpty)
            F.map(streamPutAndReads(a, first, reads)) { _ =>
              onPut(rightUnit)
              F.unit
            }
          else {
            onPut(rightUnit)
            pureToken
          }
        } else {
          unsafePut(a)(onPut) // retry
        }
    }

  // Impure function meant to cancel the put request
  @tailrec private def unsafeCancelPut(id: Id): Unit =
    stateRef.get() match {
      case current @ WaitForTake(_, listeners) =>
        val update = current.copy(listeners = listeners - id)
        if (!stateRef.compareAndSet(current, update)) {
          unsafeCancelPut(id) // retry
        }
      case _ =>
        ()
    }

  @tailrec
  private def unsafeTryTake(): F[Option[A]] = {
    val current: State[A] = stateRef.get
    current match {
      case WaitForTake(value, queue) =>
        if (queue.isEmpty) {
          if (stateRef.compareAndSet(current, State.empty))
            F.pure(Some(value))
          else {
            unsafeTryTake() // retry
          }
        } else {
          val ((ax, notify), xs) = queue.dequeue
          val update = WaitForTake(ax, xs)
          if (stateRef.compareAndSet(current, update)) {
            // Complete the `put` request waiting on a notification
            F.map(F.start(F.delay(notify(rightUnit))))(_ => Some(value))
          } else {
            unsafeTryTake() // retry
          }
        }

      case WaitForPut(_, _) =>
        F.pure(None)
    }
  }

  @tailrec
  private def unsafeTake(onTake: Listener[A]): F[F[Unit]] =
    stateRef.get match {
      case current @ WaitForTake(value, queue) =>
        if (queue.isEmpty) {
          if (stateRef.compareAndSet(current, State.empty)) {
            onTake(Right(value))
            pureToken
          } else {
            unsafeTake(onTake) // retry
          }
        } else {
          val ((ax, notify), xs) = queue.dequeue
          if (stateRef.compareAndSet(current, WaitForTake(ax, xs))) {
            F.map(F.start(F.delay(notify(rightUnit)))) { _ =>
              onTake(Right(value))
              F.unit
            }
          } else {
            unsafeTake(onTake) // retry
          }
        }

      case current @ WaitForPut(reads, takes) =>
        val id = new Id
        val newQueue = takes.updated(id, onTake)
        if (stateRef.compareAndSet(current, WaitForPut(reads, newQueue)))
          F.pure(F.delay(unsafeCancelTake(id)))
        else {
          unsafeTake(onTake) // retry
        }
    }

  @tailrec private def unsafeCancelTake(id: Id): Unit =
    stateRef.get() match {
      case current @ WaitForPut(reads, takes) =>
        val newMap = takes - id
        val update: State[A] = WaitForPut(reads, newMap)
        if (!stateRef.compareAndSet(current, update)) {
          unsafeCancelTake(id)
        }
      case _ =>
    }
  @tailrec
  private def unsafeRead(onRead: Listener[A]): F[Unit] = {
    val current: State[A] = stateRef.get
    current match {
      case WaitForTake(value, _) =>
        // A value is available, so complete `read` immediately without
        // changing the sate
        onRead(Right(value))
        F.unit

      case WaitForPut(reads, takes) =>
        // No value available, enqueue the callback
        val id = new Id
        val newQueue = reads.updated(id, onRead)
        if (stateRef.compareAndSet(current, WaitForPut(newQueue, takes)))
          F.delay(unsafeCancelRead(id))
        else {
          unsafeRead(onRead) // retry
        }
    }
  }

  private def unsafeCancelRead(id: Id): Unit =
    stateRef.get() match {
      case current @ WaitForPut(reads, takes) =>
        val newMap = reads - id
        val update: State[A] = WaitForPut(newMap, takes)
        if (!stateRef.compareAndSet(current, update)) {
          unsafeCancelRead(id)
        }
      case _ => ()
    }

  private def streamPutAndReads(
      a: A,
      put: Listener[A],
      reads: LinkedMap[Id, Listener[A]]): F[Boolean] = {
    val value = Right(a)
    // Satisfies all current `read` requests found
    val task = streamAll(value, reads.values)
    // Continue with signaling the put
    F.flatMap(task) { _ =>
      // Satisfies the first `take` request found
      if (put ne null)
        F.map(F.start(F.delay(put(value))))(mapTrue)
      else
        trueF
    }
  }

  // For streaming a value to a whole `reads` collection
  private def streamAll(
      value: Either[Nothing, A],
      listeners: Iterable[Listener[A]]): F[Unit] = {
    var acc: F[Fiber[F, Throwable, Unit]] = null.asInstanceOf[F[Fiber[F, Throwable, Unit]]]
    val cursor = listeners.iterator
    while (cursor.hasNext) {
      val next = cursor.next()
      val task = F.start(F.delay(next(value)))
      acc = if (acc == null) task else F.flatMap(acc)(_ => task)
    }
    if (acc == null) F.unit
    else F.map(acc)(mapUnit)
  }

  private[this] val mapUnit = (_: Any) => ()
  private[this] val mapTrue = (_: Any) => true
  private[this] val trueF = F.pure(true)
  private[this] val pureToken = F.pure(F.unit)
  private[this] val rightUnit = Right(())
}

private[effect] object MVarAsync {

  /**
   * Builds an [[MVarAsync]] instance with an `initial` value.
   */
  def apply[F[_], A](initial: A)(implicit F: Async[F]): MVar[F, A] =
    new MVarAsync[F, A](State(initial))

  /**
   * Returns an empty [[MVarAsync]] instance.
   */
  def empty[F[_], A](implicit F: Async[F]): MVar[F, A] =
    new MVarAsync[F, A](State.empty)

  /**
   * Internal API — Matches the callback type in `cats.effect.Async`,
   * but we don't care about the error.
   */
  private type Listener[-A] = Either[Nothing, A] => Unit

  /**
   * Used with [[LinkedMap]] to identify callbacks that need to be cancelled.
   */
  final private class Id extends Serializable

  /**
   * ADT modelling the internal state of `MVar`.
   */
  sealed private trait State[A]

  /**
   * Private [[State]] builders.
   */
  private object State {
    private[this] val ref = WaitForPut[Any](LinkedMap.empty, LinkedMap.empty)
    def apply[A](a: A): State[A] = WaitForTake(a, LinkedMap.empty)

    /**
     * `Empty` state, reusing the same instance.
     */
    def empty[A]: State[A] = ref.asInstanceOf[State[A]]
  }

  /**
   * `MVarAsync` state signaling it has `take` callbacks
   * registered and we are waiting for one or multiple
   * `put` operations.
   */
  final private case class WaitForPut[A](
      reads: LinkedMap[Id, Listener[A]],
      takes: LinkedMap[Id, Listener[A]])
      extends State[A]

  /**
   * `MVarAsync` state signaling it has one or more values enqueued,
   * to be signaled on the next `take`.
   *
   * @param value is the first value to signal
   * @param listeners are the rest of the `put` requests, along with the
   *        callbacks that need to be called whenever the corresponding
   *        value is first in line (i.e. when the corresponding `put`
   *        is unblocked from the user's point of view)
   */
  final private case class WaitForTake[A](
      value: A,
      listeners: LinkedMap[Id, (A, Listener[Unit])])
      extends State[A]
}
