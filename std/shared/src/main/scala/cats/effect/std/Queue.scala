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

package cats
package effect
package std

import cats.effect.kernel.{Async, Cont, Deferred, GenConcurrent, MonadCancelThrow, Poll, Ref}
import cats.effect.kernel.syntax.all._
import cats.syntax.all._

import scala.annotation.tailrec
import scala.collection.immutable.{Queue => ScalaQueue}
import scala.collection.mutable.ListBuffer

import java.util.concurrent.atomic.{AtomicLong, AtomicLongArray}

/**
 * A purely functional, concurrent data structure which allows insertion and retrieval of
 * elements of type `A` in a first-in-first-out (FIFO) manner.
 *
 * Depending on the type of queue constructed, the [[Queue#offer]] operation can block
 * semantically until sufficient capacity in the queue becomes available.
 *
 * The [[Queue#take]] operation semantically blocks when the queue is empty.
 *
 * The [[Queue#tryOffer]] and [[Queue#tryTake]] allow for usecases which want to avoid fiber
 * blocking a fiber.
 */
abstract class Queue[F[_], A] extends QueueSource[F, A] with QueueSink[F, A] { self =>

  /**
   * Modifies the context in which this queue is executed using the natural transformation `f`.
   *
   * @return
   *   a queue in the new context obtained by mapping the current one using `f`
   */
  def mapK[G[_]](f: F ~> G): Queue[G, A] =
    new Queue[G, A] {
      def offer(a: A): G[Unit] = f(self.offer(a))
      def tryOffer(a: A): G[Boolean] = f(self.tryOffer(a))
      def size: G[Int] = f(self.size)
      val take: G[A] = f(self.take)
      val tryTake: G[Option[A]] = f(self.tryTake)
    }
}

object Queue {

  /**
   * Constructs an empty, bounded queue holding up to `capacity` elements for `F` data types
   * that are [[cats.effect.kernel.GenConcurrent]]. When the queue is full (contains exactly
   * `capacity` elements), every next [[Queue#offer]] will be backpressured (i.e. the
   * [[Queue#offer]] blocks semantically).
   *
   * @param capacity
   *   the maximum capacity of the queue
   * @return
   *   an empty, bounded queue
   */
  def bounded[F[_], A](capacity: Int)(implicit F: GenConcurrent[F, _]): F[Queue[F, A]] = {
    assertNonNegative(capacity)

    // async queue can't handle capacity == 1 and allocates eagerly, so cap at 64k
    if (1 < capacity && capacity < Short.MaxValue.toInt * 2) {
      F match {
        case f0: Async[F] =>
          boundedForAsync[F, A](capacity)(f0)

        case _ =>
          boundedForConcurrent[F, A](capacity)
      }
    } else if (capacity > 0) {
      boundedForConcurrent[F, A](capacity)
    } else {
      synchronous[F, A]
    }
  }

  private[effect] def boundedForConcurrent[F[_], A](capacity: Int)(
      implicit F: GenConcurrent[F, _]): F[Queue[F, A]] =
    F.ref(State.empty[F, A]).map(new BoundedQueue(capacity, _))

  private[effect] def boundedForAsync[F[_], A](capacity: Int)(
      implicit F: Async[F]): F[Queue[F, A]] =
    F.delay(new BoundedAsyncQueue(capacity))

  private[effect] def unboundedForConcurrent[F[_], A](
      implicit F: GenConcurrent[F, _]): F[Queue[F, A]] =
    boundedForConcurrent[F, A](Int.MaxValue)

  private[effect] def unboundedForAsync[F[_], A](implicit F: Async[F]): F[Queue[F, A]] =
    F.delay(new UnboundedAsyncQueue())

  private[effect] def droppingForConcurrent[F[_], A](capacity: Int)(
      implicit F: GenConcurrent[F, _]): F[Queue[F, A]] =
    F.ref(State.empty[F, A]).map(new DroppingQueue(capacity, _))

  private[effect] def droppingForAsync[F[_], A](capacity: Int)(
      implicit F: Async[F]): F[Queue[F, A]] =
    F.delay(new DroppingAsyncQueue(capacity))

  /**
   * Creates a new `Queue` subject to some `capacity` bound which supports a side-effecting
   * `tryOffer` function, allowing impure code to directly add values to the queue without
   * indirecting through something like [[Dispatcher]]. This can improve performance
   * significantly in some common cases. Note that the queue produced by this constructor can be
   * used as a perfectly conventional [[Queue]] (as it is a subtype).
   *
   * @param capacity
   *   the maximum capacity of the queue (must be strictly greater than 1 and less than 32768)
   * @return
   *   an empty bounded queue
   * @see
   *   [[cats.effect.std.unsafe.BoundedQueue]]
   */
  def unsafeBounded[F[_], A](capacity: Int)(
      implicit F: Async[F]): F[unsafe.BoundedQueue[F, A]] = {
    require(capacity > 1 && capacity < Short.MaxValue.toInt * 2)
    F.delay(new BoundedAsyncQueue(capacity))
  }

  /**
   * Constructs a queue through which a single element can pass only in the case when there are
   * at least one taking fiber and at least one offering fiber for `F` data types that are
   * [[cats.effect.kernel.GenConcurrent]]. Both [[Queue#offer]] and [[Queue#take]] semantically
   * block until there is a fiber executing the opposite action, at which point both fibers are
   * freed.
   *
   * @return
   *   a synchronous queue
   */
  def synchronous[F[_], A](implicit F: GenConcurrent[F, _]): F[Queue[F, A]] =
    F.ref(SyncState.empty[F, A]).map(new Synchronous(_))

  /**
   * Constructs an empty, unbounded queue for `F` data types that are
   * [[cats.effect.kernel.GenConcurrent]]. [[Queue#offer]] never blocks semantically, as there
   * is always spare capacity in the queue.
   *
   * @return
   *   an empty, unbounded queue
   */
  def unbounded[F[_], A](implicit F: GenConcurrent[F, _]): F[Queue[F, A]] =
    F match {
      case f0: Async[F] =>
        unboundedForAsync(f0)

      case _ =>
        unboundedForConcurrent
    }

  /**
   * Creates a new unbounded `Queue` which supports a side-effecting `offer` function, allowing
   * impure code to directly add values to the queue without indirecting through something like
   * [[Dispatcher]]. This can improve performance significantly in some common cases. Note that
   * the queue produced by this constructor can be used as a perfectly conventional [[Queue]]
   * (as it is a subtype).
   *
   * @return
   *   an empty unbounded queue
   * @see
   *   [[cats.effect.std.unsafe.UnboundedQueue]]
   */
  def unsafeUnbounded[F[_], A](implicit F: Async[F]): F[unsafe.UnboundedQueue[F, A]] =
    F.delay(new UnboundedAsyncQueue())

  /**
   * Constructs an empty, bounded, dropping queue holding up to `capacity` elements for `F` data
   * types that are [[cats.effect.kernel.GenConcurrent]]. When the queue is full (contains
   * exactly `capacity` elements), every next [[Queue#offer]] will be ignored, i.e. no other
   * elements can be enqueued until there is sufficient capacity in the queue, and the offer
   * effect itself will not semantically block.
   *
   * @param capacity
   *   the maximum capacity of the queue
   * @return
   *   an empty, bounded, dropping queue
   */
  def dropping[F[_], A](capacity: Int)(implicit F: GenConcurrent[F, _]): F[Queue[F, A]] = {
    assertPositive(capacity, "Dropping")
    // async queue can't handle capacity == 1 and allocates eagerly, so cap at 64k
    if (1 < capacity && capacity < Short.MaxValue.toInt * 2) {
      F match {
        case f0: Async[F] =>
          droppingForAsync[F, A](capacity)(f0)

        case _ =>
          droppingForConcurrent[F, A](capacity)
      }
    } else {
      droppingForConcurrent[F, A](capacity)
    }
  }

  /**
   * Constructs an empty, bounded, circular buffer queue holding up to `capacity` elements for
   * `F` data types that are [[cats.effect.kernel.GenConcurrent]]. The queue always keeps at
   * most `capacity` number of elements, with the oldest element in the queue always being
   * dropped in favor of a new elements arriving in the queue, and the offer effect itself will
   * not semantically block.
   *
   * @param capacity
   *   the maximum capacity of the queue
   * @return
   *   an empty, bounded, sliding queue
   */
  def circularBuffer[F[_], A](capacity: Int)(
      implicit F: GenConcurrent[F, _]): F[Queue[F, A]] = {
    assertPositive(capacity, "CircularBuffer")
    F.ref(State.empty[F, A]).map(new CircularBufferQueue(capacity, _))
  }

  private def assertNonNegative(capacity: Int): Unit =
    if (capacity < 0)
      throw new IllegalArgumentException(
        s"Bounded queue capacity must be non-negative, was: $capacity")
    else ()

  private def assertPositive(capacity: Int, name: String): Unit =
    if (capacity <= 0)
      throw new IllegalArgumentException(
        s"$name queue capacity must be positive, was: $capacity")
    else ()

  private final class Synchronous[F[_], A](stateR: Ref[F, SyncState[F, A]])(
      implicit F: GenConcurrent[F, _])
      extends Queue[F, A] {

    def offer(a: A): F[Unit] =
      F.deferred[Boolean] flatMap { latch =>
        F uncancelable { poll =>
          // this is the 2-phase commit
          // we don't need an onCancel for latch.get since if *we* are canceled, it's okay to drop the value
          val checkCommit = poll(latch.get).ifM(F.unit, poll(offer(a)))

          val modificationF = stateR modify {
            case SyncState(offerers, takers) if takers.nonEmpty =>
              val (taker, tail) = takers.dequeue

              val finish = taker.complete((a, latch)) *> checkCommit
              SyncState(offerers, tail) -> finish

            case SyncState(offerers, takers) =>
              val cleanupF = stateR update {
                case SyncState(offerers, takers) =>
                  SyncState(offerers.filter(_._2 ne latch), takers)
              }

              // if we're canceled here, make sure we clean up
              SyncState(offerers.enqueue((a, latch)), takers) -> checkCommit.onCancel(cleanupF)
          }

          modificationF.flatten
        }
      }

    def tryOffer(a: A): F[Boolean] =
      stateR.flatModify {
        case SyncState(offerers, takers) if takers.nonEmpty =>
          val (taker, tail) = takers.dequeue

          val commitF = F.deferred[Boolean] flatMap { latch =>
            F uncancelable { poll =>
              // this won't block for long since we complete quickly in this handoff
              taker.complete((a, latch)) *> poll(latch.get)
            }
          }

          SyncState(offerers, tail) -> commitF

        case st =>
          st -> F.pure(false)
      }

    val take: F[A] =
      F.deferred[(A, Deferred[F, Boolean])] flatMap { latch =>
        F uncancelable { poll =>
          val modificationF = stateR modify {
            case SyncState(offerers, takers) if offerers.nonEmpty =>
              val ((value, offerer), tail) = offerers.dequeue
              SyncState(tail, takers) -> offerer.complete(true).as(value) // can't be canceled

            case SyncState(offerers, takers) =>
              val cleanupF = {
                val removeListener = stateR modify {
                  case SyncState(offerers, takers) =>
                    // like filter, but also returns a Boolean indicating whether it was found
                    @tailrec
                    def filterFound[Z <: AnyRef](
                        in: ScalaQueue[Z],
                        out: ScalaQueue[Z]): (Boolean, ScalaQueue[Z]) = {

                      if (in.isEmpty) {
                        (false, out)
                      } else {
                        val (head, tail) = in.dequeue

                        if (head eq latch)
                          (true, out ++ tail)
                        else
                          filterFound(tail, out.enqueue(head))
                      }
                    }

                    val (found, takers2) = filterFound(takers, ScalaQueue())
                    SyncState(offerers, takers2) -> found
                }

                val failCommit = latch.get flatMap {
                  // this is where we *fail* the 2-phase commit up in offer
                  case (_, commitLatch) => commitLatch.complete(false).void
                }

                // if we *don't* find our latch, it means an offerer has it
                // we need to wait to handshake with them
                removeListener.ifM(F.unit, failCommit)
              }

              val awaitF = poll(latch.get).onCancel(cleanupF) flatMap {
                case (a, latch) => latch.complete(true).as(a)
              }

              SyncState(offerers, takers.enqueue(latch)) -> awaitF
          }

          modificationF.flatten
        }
      }

    val tryTake: F[Option[A]] =
      F uncancelable { _ =>
        stateR.flatModify {
          case SyncState(offerers, takers) if offerers.nonEmpty =>
            val ((value, offerer), tail) = offerers.dequeue
            SyncState(tail, takers) -> offerer.complete(true).as(value.some)

          case st =>
            st -> none[A].pure[F]
        }
      }

    val size: F[Int] = F.pure(0)
  }

  /*
   * From first principles, some of the asymmetry here is justified by the fact that the offerer
   * already has a value, so if the offerer is canceled, it's not at all unusual for that value to
   * be "lost". The taker starts out *without* a value, so it's the taker-cancelation situation
   * where we must take extra care to ensure atomicity.
   *
   * The booleans here indicate whether the taker was canceled after acquiring the value. This
   * allows the taker to signal back to the offerer whether it should retry (because the taker was
   * canceled), while the offerer is careful to block until that signal is received. The tradeoff
   * here (aside from added complexity) is that operations like tryOffer now can block for a short
   * time, and tryTake becomes uncancelable.
   */
  private final case class SyncState[F[_], A](
      offerers: ScalaQueue[(A, Deferred[F, Boolean])],
      takers: ScalaQueue[Deferred[F, (A, Deferred[F, Boolean])]])

  private object SyncState {
    def empty[F[_], A]: SyncState[F, A] = SyncState(ScalaQueue(), ScalaQueue())
  }

  private sealed abstract class AbstractQueue[F[_], A](
      capacity: Int,
      state: Ref[F, State[F, A]]
  )(implicit F: GenConcurrent[F, _])
      extends Queue[F, A] {

    protected def onOfferNoCapacity(
        s: State[F, A],
        a: A,
        offerer: Deferred[F, Unit],
        poll: Poll[F],
        recurse: => F[Unit]): (State[F, A], F[Unit])

    protected def onTryOfferNoCapacity(s: State[F, A], a: A): (State[F, A], F[Boolean])

    def offer(a: A): F[Unit] =
      F uncancelable { poll =>
        F.deferred[Unit] flatMap { offerer =>
          val modificationF = state modify {
            case State(queue, size, takers, offerers) if takers.nonEmpty =>
              val (taker, rest) = takers.dequeue
              State(queue.enqueue(a), size + 1, rest, offerers) -> taker.complete(()).void

            case State(queue, size, takers, offerers) if size < capacity =>
              State(queue.enqueue(a), size + 1, takers, offerers) -> F.unit

            case s =>
              onOfferNoCapacity(s, a, offerer, poll, offer(a))
          }

          modificationF.flatten
        }
      }

    def tryOffer(a: A): F[Boolean] =
      state.flatModify {
        case State(queue, size, takers, offerers) if takers.nonEmpty =>
          val (taker, rest) = takers.dequeue
          State(queue.enqueue(a), size + 1, rest, offerers) -> taker.complete(()).as(true)

        case State(queue, size, takers, offerers) if size < capacity =>
          State(queue.enqueue(a), size + 1, takers, offerers) -> F.pure(true)

        case s =>
          onTryOfferNoCapacity(s, a)
      }

    val take: F[A] =
      F.uncancelable { poll =>
        F.deferred[Unit] flatMap { taker =>
          val modificationF = state modify {
            case State(queue, size, takers, offerers) if queue.nonEmpty && offerers.isEmpty =>
              val (a, rest) = queue.dequeue
              State(rest, size - 1, takers, offerers) -> F.pure(a)

            case State(queue, size, takers, offerers) if queue.nonEmpty =>
              val (a, rest) = queue.dequeue

              // if we haven't made enough space for a new offerer, don't disturb the water
              if (size - 1 < capacity) {
                val (release, tail) = offerers.dequeue
                State(rest, size - 1, takers, tail) -> release.complete(()).as(a)
              } else {
                State(rest, size - 1, takers, offerers) -> F.pure(a)
              }

            case State(queue, size, takers, offerers) =>
              /*
               * In the case that we're notified as we're canceled and the cancelation wins the
               * race, we need to not only remove ourselves from the queue but also grab the next
               * in line and notify *them*. Since this scenario cannot be detected reliably, we
               * just unconditionally notify. If the notification was spurious, the taker we notify
               * will end up going to the back of the queue, violating fairness.
               */
              val cleanup = state modify { s =>
                val takers2 = s.takers.filter(_ ne taker)
                if (takers2.isEmpty) {
                  s.copy(takers = takers2) -> F.unit
                } else {
                  val (taker, rest) = takers2.dequeue
                  s.copy(takers = rest) -> taker.complete(()).void
                }
              }

              val await = poll(taker.get).onCancel(cleanup.flatten) *>
                poll(take).onCancel(notifyNextTaker.flatten)

              val (fulfill, offerers2) = if (offerers.isEmpty) {
                (await, offerers)
              } else {
                val (release, rest) = offerers.dequeue
                (release.complete(()) *> await, rest)
              }

              // it would be safe to throw cleanup around *both* polls, but we micro-optimize slightly here
              State(queue, size, takers.enqueue(taker), offerers2) -> fulfill
          }

          modificationF.flatten
        }
      }

    val tryTake: F[Option[A]] =
      state.flatModify {
        case State(queue, size, takers, offerers) if queue.nonEmpty && offerers.isEmpty =>
          val (a, rest) = queue.dequeue
          State(rest, size - 1, takers, offerers) -> F.pure(a.some)

        case State(queue, size, takers, offerers) if queue.nonEmpty =>
          val (a, rest) = queue.dequeue
          val (release, tail) = offerers.dequeue
          State(rest, size - 1, takers, tail) -> release.complete(()).as(a.some)

        case s =>
          s -> F.pure(none[A])
      }

    val size: F[Int] = state.get.map(_.size)

    private[this] val notifyNextTaker = state modify { s =>
      if (s.takers.isEmpty) {
        s -> F.unit
      } else {
        val (taker, rest) = s.takers.dequeue
        s.copy(takers = rest) -> taker.complete(()).void
      }
    }
  }

  private final class BoundedQueue[F[_], A](capacity: Int, state: Ref[F, State[F, A]])(
      implicit F: GenConcurrent[F, _]
  ) extends AbstractQueue(capacity, state) {

    require(capacity > 0)

    protected def onOfferNoCapacity(
        s: State[F, A],
        a: A,
        offerer: Deferred[F, Unit],
        poll: Poll[F],
        recurse: => F[Unit]): (State[F, A], F[Unit]) = {

      val State(queue, size, takers, offerers) = s

      val cleanup = state modify { s =>
        val offerers2 = s.offerers.filter(_ ne offerer)

        if (offerers2.isEmpty) {
          s.copy(offerers = offerers2) -> F.unit
        } else {
          val (offerer, rest) = offerers2.dequeue
          s.copy(offerers = rest) -> offerer.complete(()).void
        }
      }

      State(queue, size, takers, offerers.enqueue(offerer)) ->
        (poll(offerer.get) *> poll(recurse)).onCancel(cleanup.flatten)
    }

    protected def onTryOfferNoCapacity(s: State[F, A], a: A): (State[F, A], F[Boolean]) =
      s -> F.pure(false)
  }

  private final class DroppingQueue[F[_], A](capacity: Int, state: Ref[F, State[F, A]])(
      implicit F: GenConcurrent[F, _]
  ) extends AbstractQueue(capacity, state) {

    protected def onOfferNoCapacity(
        s: State[F, A],
        a: A,
        offerer: Deferred[F, Unit],
        poll: Poll[F],
        recurse: => F[Unit]
    ): (State[F, A], F[Unit]) =
      s -> F.unit

    protected def onTryOfferNoCapacity(s: State[F, A], a: A): (State[F, A], F[Boolean]) =
      s -> F.pure(false)
  }

  private final class CircularBufferQueue[F[_], A](capacity: Int, state: Ref[F, State[F, A]])(
      implicit F: GenConcurrent[F, _]
  ) extends AbstractQueue(capacity, state) {

    protected def onOfferNoCapacity(
        s: State[F, A],
        a: A,
        offerer: Deferred[F, Unit],
        poll: Poll[F],
        recurse: => F[Unit]): (State[F, A], F[Unit]) = {
      // dotty doesn't like cats map on tuples
      val (ns, fb) = onTryOfferNoCapacity(s, a)
      (ns, fb.void)
    }

    protected def onTryOfferNoCapacity(s: State[F, A], a: A): (State[F, A], F[Boolean]) = {
      val State(queue, size, takers, offerers) = s
      val (_, rest) = queue.dequeue
      State(rest.enqueue(a), size, takers, offerers) -> F.pure(true)
    }

  }

  private final case class State[F[_], A](
      queue: ScalaQueue[A],
      size: Int,
      takers: ScalaQueue[Deferred[F, Unit]],
      offerers: ScalaQueue[Deferred[F, Unit]])

  private object State {
    def empty[F[_], A]: State[F, A] =
      State(ScalaQueue.empty, 0, ScalaQueue.empty, ScalaQueue.empty)
  }

  private val EitherUnit: Either[Nothing, Unit] = Right(())

  private abstract class BaseBoundedAsyncQueue[F[_], A](capacity: Int)(implicit F: Async[F])
      extends Queue[F, A] {

    require(capacity > 1)

    protected[this] val buffer = new UnsafeBounded[A](capacity)

    protected[this] val takers = new UnsafeUnbounded[Either[Throwable, Unit] => Unit]()
    protected[this] val offerers = new UnsafeUnbounded[Either[Throwable, Unit] => Unit]()

    protected[this] val FailureSignal = cats.effect.std.FailureSignal // prefetch

    // private[this] val takers = new ConcurrentLinkedQueue[AtomicReference[Either[Throwable, Unit] => Unit]]()
    // private[this] val offerers = new ConcurrentLinkedQueue[AtomicReference[Either[Throwable, Unit] => Unit]]()

    val size: F[Int] = F.delay(buffer.size())

    val take: F[A] =
      F uncancelable { poll =>
        F defer {
          try {
            // attempt to take from the buffer. if it's empty, this will raise an exception
            val result = buffer.take()
            // println(s"took: size = ${buffer.size()}")

            // still alive! notify an offerer that there's some space
            notifyOne(offerers)
            F.pure(result)
          } catch {
            case FailureSignal =>
              // buffer was empty
              // capture the fact that our retry succeeded and the value we were able to take
              var received = false
              var result: A = null.asInstanceOf[A]

              // a latch to block until some offerer wakes us up
              // we need to use cont to avoid calling `get` on the double-check
              val wait = F.cont[Unit, Unit](new Cont[F, Unit, Unit] {
                override def apply[G[_]](implicit G: MonadCancelThrow[G]) = { (k, get, lift) =>
                  G uncancelable { poll =>
                    val lifted = lift {
                      F delay {
                        // register ourselves as a listener for offers
                        val clear = takers.put(k)

                        try {
                          // now that we're registered, retry the take
                          result = buffer.take()

                          // it worked! clear out our listener
                          clear()
                          // we got a result, so received should be true now
                          received = true

                          // we *might* have negated a notification by succeeding here
                          // unnecessary wake-ups are mostly harmless (only slight fairness loss)
                          notifyOne(takers)

                          // it's technically possible to already have queued offerers. wake up one of them
                          notifyOne(offerers)

                          // we skip `get` here because we already have a value
                          // this is the thing that `async` doesn't allow us to do
                          G.unit
                        } catch {
                          case FailureSignal =>
                            // println(s"failed take size = ${buffer.size()}")
                            // our retry failed, we're registered as a listener, so suspend
                            poll(get).onCancel(lift(F.delay(clear())))
                        }
                      }
                    }

                    lifted.flatten
                  }
                }
              })

              val notifyAnyway = F delay {
                // we might have been awakened and canceled simultaneously
                // try waking up another taker just in case
                notifyOne(takers)
              }

              // suspend until an offerer wakes us or our retry succeeds, then return a result
              (poll(wait) *> F.defer(if (received) F.pure(result) else poll(take)))
                .onCancel(notifyAnyway)
          }
        }
      }

    val tryTake: F[Option[A]] = F delay {
      try {
        val back = buffer.take()
        notifyOne(offerers)
        Some(back)
      } catch {
        case FailureSignal =>
          None
      }
    }

    override def tryTakeN(limit: Option[Int])(implicit F0: Monad[F]): F[List[A]] = {
      QueueSource.assertMaxNPositive(limit)

      F delay {
        val _ = F0
        val back = buffer.drain(limit.getOrElse(Int.MaxValue))

        @tailrec
        def loop(i: Int): Unit = {
          if (i >= 0) {
            var took = false

            val f =
              try {
                val back = offerers.take()
                took = true
                back
              } catch {
                case FailureSignal => null
              }

            if (took) {
              if (f != null) {
                f(EitherUnit)
                loop(i - 1)
              } else {
                loop(i)
              }
            }
          }
        }

        // notify up to back.length offerers
        loop(back.length)
        back
      }
    }

    def debug(): Unit = {
      println(s"buffer: ${buffer.debug()}")
      // println(s"takers: ${takers.debug()}")
      // println(s"offerers: ${offerers.debug()}")
    }

    // TODO could optimize notifications by checking if buffer is completely empty on put
    @tailrec
    protected[this] final def notifyOne(
        waiters: UnsafeUnbounded[Either[Throwable, Unit] => Unit]): Unit = {
      // capture whether or not we should loop (structured in this way to avoid nested try/catch, which has a performance cost)
      val retry =
        try {
          val f =
            waiters
              .take() // try to take the first waiter; if there are none, raise an exception

          // we didn't get an exception, but the waiter may have been removed due to cancelation
          if (f == null) {
            // it was removed! loop and retry
            true
          } else {
            // it wasn't removed, so invoke it
            // taker may have already been invoked due to the double-check pattern, in which case this will be idempotent
            f(EitherUnit)

            // don't retry
            false
          }
        } catch {
          // there are no takers, so don't notify anything
          case FailureSignal => false
        }

      if (retry) {
        // loop outside of try/catch
        notifyOne(waiters)
      }
    }
  }

  /*
   * Does not correctly handle bound = 0 because take waiters are async[Unit]
   */
  private final class BoundedAsyncQueue[F[_], A](capacity: Int)(implicit F: Async[F])
      extends BaseBoundedAsyncQueue[F, A](capacity)
      with unsafe.BoundedQueue[F, A] {

    def offer(a: A): F[Unit] =
      F uncancelable { poll =>
        F defer {
          try {
            // attempt to put into the buffer; if the buffer is full, it will raise an exception
            buffer.put(a)
            // println(s"offered: size = ${buffer.size()}")

            // we successfully put, if there are any takers, grab the first one and wake it up
            notifyOne(takers)
            F.unit
          } catch {
            case FailureSignal =>
              // capture whether or not we were successful in our retry
              var succeeded = false

              // a latch blocking until some taker notifies us
              val wait = F.async[Unit] { k =>
                F delay {
                  // add ourselves to the listeners queue
                  val clear = offerers.put(k)

                  try {
                    // now that we're listening, re-attempt putting
                    buffer.put(a)

                    // it worked! clear ourselves out of the queue
                    clear()
                    // our retry succeeded
                    succeeded = true

                    // manually complete our own callback
                    // note that we could have a race condition here where we're already completed
                    // async will deduplicate these calls for us
                    // additionally, the continuation (below) is held until the registration completes
                    k(EitherUnit)

                    // we *might* have negated a notification by succeeding here
                    // unnecessary wake-ups are mostly harmless (only slight fairness loss)
                    notifyOne(offerers)

                    // technically it's possible to already have waiting takers. notify one of them
                    notifyOne(takers)

                    // we're immediately complete, so no point in creating a finalizer
                    None
                  } catch {
                    case FailureSignal =>
                      // our retry failed, meaning the queue is still full and we're listening, so suspend
                      // println(s"failed offer size = ${buffer.size()}")
                      Some(F.delay(clear()))
                  }
                }
              }

              val notifyAnyway = F delay {
                // we might have been awakened and canceled simultaneously
                // try waking up another offerer just in case
                notifyOne(offerers)
              }

              // suspend until the buffer put can succeed
              // if succeeded is true then we've *already* put
              // if it's false, then some taker woke us up, so race the retry with other offers
              (poll(wait) *> F.defer(if (succeeded) F.unit else poll(offer(a))))
                .onCancel(notifyAnyway)
          }
        }
      }

    def unsafeTryOffer(a: A): Boolean = {
      try {
        buffer.put(a)
        notifyOne(takers)
        true
      } catch {
        case FailureSignal =>
          false
      }
    }

    def tryOffer(a: A): F[Boolean] = F.delay(unsafeTryOffer(a))

  }

  private final class UnboundedAsyncQueue[F[_], A]()(implicit F: Async[F])
      extends Queue[F, A]
      with unsafe.UnboundedQueue[F, A] {

    private[this] val buffer = new UnsafeUnbounded[A]()
    private[this] val takers = new UnsafeUnbounded[Either[Throwable, Unit] => Unit]()
    private[this] val FailureSignal = cats.effect.std.FailureSignal // prefetch

    def unsafeOffer(a: A): Unit = {
      buffer.put(a)
      notifyOne()
    }

    def offer(a: A): F[Unit] = F.delay(unsafeOffer(a))

    def tryOffer(a: A): F[Boolean] = F delay {
      buffer.put(a)
      notifyOne()
      true
    }

    val size: F[Int] = F.delay(buffer.size())

    val take: F[A] = F uncancelable { poll =>
      F defer {
        try {
          // attempt to take from the buffer. if it's empty, this will raise an exception
          F.pure(buffer.take())
        } catch {
          case FailureSignal =>
            // buffer was empty
            // capture the fact that our retry succeeded and the value we were able to take
            var received = false
            var result: A = null.asInstanceOf[A]

            // a latch to block until some offerer wakes us up
            val wait = F.asyncCheckAttempt[Unit] { k =>
              F delay {
                // register ourselves as a listener for offers
                val clear = takers.put(k)

                try {
                  // now that we're registered, retry the take
                  result = buffer.take()

                  // it worked! clear out our listener
                  clear()
                  // we got a result, so received should be true now
                  received = true

                  // we *might* have negated a notification by succeeding here
                  // unnecessary wake-ups are mostly harmless (only slight fairness loss)
                  notifyOne()

                  // don't bother with a finalizer since we're already complete
                  EitherUnit
                } catch {
                  case FailureSignal =>
                    // println(s"failed take size = ${buffer.size()}")
                    // our retry failed, we're registered as a listener, so suspend
                    Left(Some(F.delay(clear())))
                }
              }
            }

            val notifyAnyway = F delay {
              // we might have been awakened and canceled simultaneously
              // try waking up another taker just in case
              notifyOne()
            }

            // suspend until an offerer wakes us or our retry succeeds, then return a result
            (poll(wait) *> F.defer(if (received) F.pure(result) else poll(take)))
              .onCancel(notifyAnyway)
        }
      }
    }

    val tryTake: F[Option[A]] = F delay {
      try {
        Some(buffer.take())
      } catch {
        case FailureSignal =>
          None
      }
    }

    @tailrec
    private[this] def notifyOne(): Unit = {
      // capture whether or not we should loop (structured in this way to avoid nested try/catch, which has a performance cost)
      val retry =
        try {
          val f =
            takers.take() // try to take the first waiter; if there are none, raise an exception

          // we didn't get an exception, but the waiter may have been removed due to cancelation
          if (f == null) {
            // it was removed! loop and retry
            true
          } else {
            // it wasn't removed, so invoke it
            // taker may have already been invoked due to the double-check pattern, in which case this will be idempotent
            f(EitherUnit)

            // don't retry
            false
          }
        } catch {
          // there are no takers, so don't notify anything
          case FailureSignal => false
        }

      if (retry) {
        // loop outside of try/catch
        notifyOne()
      }
    }
  }

  private final class DroppingAsyncQueue[F[_], A](capacity: Int)(implicit F: Async[F])
      extends BaseBoundedAsyncQueue[F, A](capacity) {

    def offer(a: A): F[Unit] =
      F.delay {
        tryOfferUnsafe(a)
        ()
      }

    def tryOffer(a: A): F[Boolean] =
      F.delay(tryOfferUnsafe(a))

    private def tryOfferUnsafe(a: A): Boolean =
      try {
        buffer.put(a)
        notifyOne(takers)
        true
      } catch {
        case FailureSignal =>
          false
      }
  }

  // ported with love from https://github.com/JCTools/JCTools/blob/master/jctools-core/src/main/java/org/jctools/queues/MpmcArrayQueue.java
  private[effect] final class UnsafeBounded[A](bound: Int) {
    require(bound > 1)

    private[this] val buffer = new Array[AnyRef](bound)

    private[this] val sequenceBuffer = new AtomicLongArray(bound)
    private[this] val head = new AtomicLong(0)
    private[this] val tail = new AtomicLong(0)

    private[this] val LookAheadStep = Math.max(2, Math.min(bound / 4, 4096)) // TODO tunable

    private[this] val FailureSignal = cats.effect.std.FailureSignal // prefetch

    0.until(bound).foreach(i => sequenceBuffer.set(i, i.toLong))

    def debug(): String = buffer.mkString("[", ", ", "]")

    @tailrec
    def size(): Int = {
      val before = head.get()
      val currentTail = tail.get()
      val after = head.get()

      if (before == after) {
        val size = currentTail - after

        if (size < 0)
          0
        else
          size.toInt
      } else {
        size()
      }
    }

    def put(data: A): Unit = {
      @tailrec
      def loop(currentHead: Long): Long = {
        val currentTail = tail.get()
        val seq = sequenceBuffer.get(project(currentTail))

        if (seq < currentTail) {
          if (currentTail - bound >= currentHead) {
            val currentHead2 = head.get()

            if (currentTail - bound >= currentHead2)
              throw FailureSignal
            else
              loop(currentHead2)
          } else {
            loop(currentHead)
          }
        } else {
          if (seq == currentTail && tail.compareAndSet(currentTail, currentTail + 1))
            currentTail
          else
            loop(currentHead)
        }
      }

      val currentTail = loop(Long.MinValue)

      buffer(project(currentTail)) = data.asInstanceOf[AnyRef]
      sequenceBuffer.incrementAndGet(project(currentTail))

      ()
    }

    def take(): A = {
      @tailrec
      def loop(currentTail: Long): Long = {
        val currentHead = head.get()
        val seq = sequenceBuffer.get(project(currentHead))

        if (seq < currentHead + 1) {
          if (currentHead >= currentTail) {
            val currentTail2 = tail.get()

            if (currentHead == currentTail2)
              throw FailureSignal
            else
              loop(currentTail2)
          } else {
            loop(currentTail)
          }
        } else {
          if (seq == currentHead + 1 && head.compareAndSet(currentHead, currentHead + 1))
            currentHead
          else
            loop(currentTail)
        }
      }

      val currentHead = loop(-1)

      val back = buffer(project(currentHead)).asInstanceOf[A]
      buffer(project(currentHead)) = null
      sequenceBuffer.set(project(currentHead), currentHead + bound)

      back
    }

    def drain(limit: Int): List[A] = {
      val back = new ListBuffer[A]()

      @tailrec
      def loopOne(consumed: Int): Unit = {
        if (consumed < limit) {
          val next =
            try {
              back += take()
              true
            } catch {
              case FailureSignal => false
            }

          if (next) {
            loopOne(consumed + 1)
          }
        }
      }

      val maxLookAheadStep = Math.min(LookAheadStep, limit)

      @tailrec
      def loopMany(consumed: Int): Unit = {
        if (consumed < limit) {
          val remaining = limit - consumed
          val step = Math.min(remaining, maxLookAheadStep)

          val currentHead = head.get()
          val lookAheadIndex = currentHead + step - 1
          val lookAheadOffset = project(lookAheadIndex)
          val lookAheadSeq = sequenceBuffer.get(lookAheadOffset)
          val expectedLookAheadSeq = lookAheadIndex + 1

          if (lookAheadSeq == expectedLookAheadSeq && head.compareAndSet(
              currentHead,
              expectedLookAheadSeq)) {
            var i = 0
            while (i < step) {
              val index = currentHead + i
              val offset = project(index)
              val expectedSeq = index + 1

              while (sequenceBuffer.get(offset) != expectedSeq) {}

              val value = buffer(offset).asInstanceOf[A]
              buffer(offset) = null
              sequenceBuffer.set(offset, index + bound)
              back += value

              i += 1
            }

            loopMany(consumed + step)
          } else {
            if (lookAheadSeq < expectedLookAheadSeq) {
              if (sequenceBuffer.get(project(currentHead)) >= currentHead + 1) {
                loopOne(consumed)
              }
            } else {
              loopOne(consumed)
            }
          }
        }
      }

      loopMany(0)
      back.toList
    }

    private[this] def project(idx: Long): Int =
      ((idx & Int.MaxValue) % bound).toInt
  }

  implicit def catsInvariantForQueue[F[_]: Functor]: Invariant[Queue[F, *]] =
    new Invariant[Queue[F, *]] {
      override def imap[A, B](fa: Queue[F, A])(f: A => B)(g: B => A): Queue[F, B] =
        new Queue[F, B] {
          override def offer(b: B): F[Unit] =
            fa.offer(g(b))
          override def tryOffer(b: B): F[Boolean] =
            fa.tryOffer(g(b))
          override def take: F[B] =
            fa.take.map(f)
          override def tryTake: F[Option[B]] =
            fa.tryTake.map(_.map(f))
          override def size: F[Int] =
            fa.size
        }
    }
}
