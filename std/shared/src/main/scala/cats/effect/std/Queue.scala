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

package cats
package effect
package std

import cats.effect.kernel.{Async, Deferred, GenConcurrent, Poll, Ref}
import cats.effect.kernel.syntax.all._
import cats.syntax.all._

import scala.annotation.tailrec
import scala.collection.immutable.{Queue => ScalaQueue}

import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

/**
 * A purely functional, concurrent data structure which allows insertion and retrieval of
 * elements of type `A` in a first-in-first-out (FIFO) manner.
 *
 * Depending on the type of queue constructed, the [[Queue#offer]] operation can block
 * semantically until sufficient capacity in the queue becomes available.
 *
 * The [[Queue#take]] operation semantically blocks when the queue is empty.
 *
 * The [[Queue#tryOffer]] and [[Queue#tryTake]] allow for usecases which want to avoid
 * semantically blocking a fiber.
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
  def bounded[F[_], A](capacity: Int)(implicit F: GenConcurrent[F, _]): F[Queue[F, A]] =
    F match {
      case f0: Async[F] => boundedForAsync[F, A](capacity)(f0)
      case _ => boundedForConcurrent[F, A](capacity)
    }

  private[effect] def boundedForConcurrent[F[_], A](capacity: Int)(implicit F: GenConcurrent[F, _]): F[Queue[F, A]] = {
    assertNonNegative(capacity)
    F.ref(State.empty[F, A]).map(new BoundedQueue(capacity, _))
  }

  private[effect] def boundedForAsync[F[_], A](capacity: Int)(implicit F: Async[F]): F[Queue[F, A]] = {
    assertNonNegative(capacity)

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
    bounded(0)

  /**
   * Constructs an empty, unbounded queue for `F` data types that are
   * [[cats.effect.kernel.GenConcurrent]]. [[Queue#offer]] never blocks semantically, as there
   * is always spare capacity in the queue.
   *
   * @return
   *   an empty, unbounded queue
   */
  def unbounded[F[_], A](implicit F: GenConcurrent[F, _]): F[Queue[F, A]] =
    bounded(Int.MaxValue)

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
    F.ref(State.empty[F, A]).map(new DroppingQueue(capacity, _))
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

  private sealed abstract class AbstractQueue[F[_], A](
      capacity: Int,
      state: Ref[F, State[F, A]]
  )(implicit F: GenConcurrent[F, _])
      extends Queue[F, A] {

    protected def onOfferNoCapacity(
        s: State[F, A],
        a: A,
        offerer: Deferred[F, Unit],
        poll: Poll[F]
    ): (State[F, A], F[Unit])

    protected def onTryOfferNoCapacity(s: State[F, A], a: A): (State[F, A], F[Boolean])

    def offer(a: A): F[Unit] =
      F.deferred[Unit].flatMap { offerer =>
        F.uncancelable { poll =>
          state.modify {
            case State(queue, size, takers, offerers) if takers.nonEmpty =>
              val (taker, rest) = takers.dequeue
              State(queue, size, rest, offerers) -> taker.complete(a).void

            case State(queue, size, takers, offerers) if size < capacity =>
              State(queue.enqueue(a), size + 1, takers, offerers) -> F.unit

            case s =>
              onOfferNoCapacity(s, a, offerer, poll)
          }.flatten
        }
      }

    def tryOffer(a: A): F[Boolean] =
      state
        .modify {
          case State(queue, size, takers, offerers) if takers.nonEmpty =>
            val (taker, rest) = takers.dequeue
            State(queue, size, rest, offerers) -> taker.complete(a).as(true)

          case State(queue, size, takers, offerers) if size < capacity =>
            State(queue.enqueue(a), size + 1, takers, offerers) -> F.pure(true)

          case s =>
            onTryOfferNoCapacity(s, a)
        }
        .flatten
        .uncancelable

    val take: F[A] =
      F.deferred[A].flatMap { taker =>
        F.uncancelable { poll =>
          state.modify {
            case State(queue, size, takers, offerers) if queue.nonEmpty && offerers.isEmpty =>
              val (a, rest) = queue.dequeue
              State(rest, size - 1, takers, offerers) -> F.pure(a)

            case State(queue, size, takers, offerers) if queue.nonEmpty =>
              val (a, rest) = queue.dequeue
              val ((move, release), tail) = offerers.dequeue
              State(rest.enqueue(move), size, takers, tail) -> release.complete(()).as(a)

            case State(queue, size, takers, offerers) if offerers.nonEmpty =>
              val ((a, release), rest) = offerers.dequeue
              State(queue, size, takers, rest) -> release.complete(()).as(a)

            case State(queue, size, takers, offerers) =>
              val cleanup = state.update { s => s.copy(takers = s.takers.filter(_ ne taker)) }
              State(queue, size, takers.enqueue(taker), offerers) ->
                poll(taker.get).onCancel(cleanup)
          }.flatten
        }
      }

    val tryTake: F[Option[A]] =
      state
        .modify {
          case State(queue, size, takers, offerers) if queue.nonEmpty && offerers.isEmpty =>
            val (a, rest) = queue.dequeue
            State(rest, size - 1, takers, offerers) -> F.pure(a.some)

          case State(queue, size, takers, offerers) if queue.nonEmpty =>
            val (a, rest) = queue.dequeue
            val ((move, release), tail) = offerers.dequeue
            State(rest.enqueue(move), size, takers, tail) -> release.complete(()).as(a.some)

          case State(queue, size, takers, offerers) if offerers.nonEmpty =>
            val ((a, release), rest) = offerers.dequeue
            State(queue, size, takers, rest) -> release.complete(()).as(a.some)

          case s =>
            s -> F.pure(none[A])
        }
        .flatten
        .uncancelable

    def size: F[Int] = state.get.map(_.size)

  }

  private final class BoundedQueue[F[_], A](capacity: Int, state: Ref[F, State[F, A]])(
      implicit F: GenConcurrent[F, _]
  ) extends AbstractQueue(capacity, state) {

    protected def onOfferNoCapacity(
        s: State[F, A],
        a: A,
        offerer: Deferred[F, Unit],
        poll: Poll[F]
    ): (State[F, A], F[Unit]) = {
      val State(queue, size, takers, offerers) = s
      val cleanup = state.update { s => s.copy(offerers = s.offerers.filter(_._2 ne offerer)) }
      State(queue, size, takers, offerers.enqueue(a -> offerer)) -> poll(offerer.get)
        .onCancel(cleanup)
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
        poll: Poll[F]
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
        poll: Poll[F]
    ): (State[F, A], F[Unit]) = {
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
      takers: ScalaQueue[Deferred[F, A]],
      offerers: ScalaQueue[(A, Deferred[F, Unit])]
  )

  private object State {
    def empty[F[_], A]: State[F, A] =
      State(ScalaQueue.empty, 0, ScalaQueue.empty, ScalaQueue.empty)
  }

  private val EitherUnit: Either[Throwable, Unit] = Right(())
  private val FailureSignal: Throwable = new RuntimeException with scala.util.control.NoStackTrace

  /*
   * This data structure is really two queues: a circular buffer and an unbounded linked queue.
   * The former contains data, while the latter contains blockers either for offering or taking.
   */
  private final class BoundedAsyncQueue[F[_], A](capacity: Int)(implicit F: Async[F]) extends Queue[F, A] {
    import java.util.concurrent.ConcurrentLinkedQueue
    import java.util.concurrent.atomic.AtomicInteger

    private[this] val buffer = new Array[AnyRef](capacity)

    private[this] val front = new AtomicInteger(0)
    private[this] val back = new AtomicInteger(0)
    private[this] val currentSize = new AtomicInteger(0)

    // TODO optimize this a bit where take waiters can be A => Unit

    def offer(a: A): F[Unit] = F defer {
      if (!_tryOffer(a)) {
        F async { k =>
          F delay {
            // waiters.put(k)
            Some(F.unit)    // TODO free the memory reference
          }
        }
      } else {
        F.unit
      }
    }

    def tryOffer(a: A): F[Boolean] = F.delay(_tryOffer(a))

    private[this] def _tryOffer(a: A): Boolean = {
      val old = front.get()
      val i = old + 1 % capacity

      if (i != back.get()) {
        if (front.compareAndSet(old, i)) {
          buffer(i) = a.asInstanceOf[AnyRef]
          currentSize.incrementAndGet()  // publish the write and permit access

          true
        } else {
          _tryOffer(a)
        }
      } else {
        false
      }
    }

    def size: F[Int] = F.delay(currentSize.get())

    val take: F[A] = ???

    val tryTake: F[Option[A]] = F.delay(Some(_tryTake()): Option[A]).handleError(_ => None)

    private[this] def _tryTake(): A = {
      if (currentSize.get() <= 0) {
        throw FailureSignal
      } else {
        val i = back.get()

        if (back.compareAndSet(i, i + 1 % capacity)) {
          currentSize.decrementAndGet()

          val result = buffer(i).asInstanceOf[A]
          buffer(i) = null
          result
        } else {
          _tryTake()
        }
      }
    }
  }

  private[effect] final class UnsafeBounded[A](bound: Int) {
    private[this] val buffer = new Array[AnyRef](bound)

    private[this] val first = new AtomicInteger(0)
    private[this] val last = new AtomicInteger(0)
    private[this] val length = new AtomicInteger(0)

    @tailrec
    def put(data: A): Unit = {
      val oldLast = last.get()

      if (length.get() >= bound) {
        throw FailureSignal
      } else {
        if (last.compareAndSet(oldLast, (oldLast + 1) % bound)) {
          buffer(oldLast) = data.asInstanceOf[AnyRef]

          // we're already exclusive with other puts, and take can only *decrease* length, so we don't gate
          // this also forms a write barrier for buffer
          length.getAndIncrement()

          ()
        } else {
          put(data)
        }
      }
    }

    @tailrec
    def take(): A = {
      val oldFirst = first.get()

      if (length.get() <= 0) {    // read barrier and check that boundary puts have completed
        throw FailureSignal
      } else {
        if (first.compareAndSet(oldFirst, (oldFirst + 1) % bound)) {
          val back = buffer(oldFirst).asInstanceOf[A]

          // we're already exclusive with other takes, and put can only *increase* length, so we don't gate
          // doing this *after* we read from the buffer ensures we don't read a new value when full
          // specifically, it guarantees that puts will fail even if first has already advanced
          length.getAndDecrement()

          buffer(oldFirst) = null   // prevent memory leaks (no need to eagerly publish)
          back
        } else {
          take()
        }
      }
    }
  }

  /*
   * Two pointers: head and tail
   *
   * insert:
   * CAS on the head == null.
   * If true, then getAndSet the tail to yourself
   *   If null then you're done.
   *   If !null then append the old tail to yourself
   *     Weak CAS the tail back to the old tail, ignoring results
   * If false then getAndSet the tail to yourself
   *   If null then weak CAS yourself to head on == null
   *   If !null then
   *     append yourself to the old tail
   *
   * take:
   * Get the head
   * If null then queue is empty
   *
   * Get the tail of the head
   * CAS the head to its tail
   * If failed then retry
   *
   * If tail is null then weak CAS the tail pointer, ignoring results
   *
   * If data is null retry
   * If data is not null
   *   empty out the cell
   *   return
   */
  private[effect] final class UnsafeUnbounded[A] {
    private[this] val first = new AtomicReference[Cell]
    private[this] val last = new AtomicReference[Cell]

    def put(data: A): Unit = {
      val cell = new Cell(data)

      val oldLast = last.getAndSet(cell)
      if (oldLast == null) {
        @tailrec
        def loop(): Unit = {
          val oldFirst = first.get()
          if (oldFirst == null) {
            if (!first.compareAndSet(null, cell)) {
              loop()
            }
          } else {
            oldFirst.append(cell)   // even if it's taken out by this point, it'll still point into the structure
          }
        }

        loop()
      } else {
        cell.append(oldLast)
        last.weakCompareAndSet(cell, oldLast)
      }
    }

    @tailrec
    def take(): A = {
      val oldFirst = first.get()
      if (oldFirst == null) {
        throw FailureSignal
      } else {
        val tail = oldFirst.get()
        if (!first.compareAndSet(oldFirst, tail)) {
          take()
        } else {
          if (tail == null) {
            last.weakCompareAndSet(oldFirst, tail)    // someone else may put in the interim; that's okay
          }

          val data = oldFirst.data()
          if (data == null) {
            take()
          } else {
            data
          }
        }
      }
    }

    private final class Cell(private[this] var _data: A) extends AtomicReference[Cell] {

      def clear(): Unit = _data = null.asInstanceOf[A]

      def data(): A = _data

      @tailrec
      def append(cell: Cell): Unit = {
        val tail = get()
        if (tail == null) {
          if (!compareAndSet(tail, cell)) {
            append(cell)
          }
        } else {
          tail.append(cell)
        }
      }
    }
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

trait QueueSource[F[_], A] {

  /**
   * Dequeues an element from the front of the queue, possibly semantically blocking until an
   * element becomes available.
   */
  def take: F[A]

  /**
   * Attempts to dequeue an element from the front of the queue, if one is available without
   * semantically blocking.
   *
   * @return
   *   an effect that describes whether the dequeueing of an element from the queue succeeded
   *   without blocking, with `None` denoting that no element was available
   */
  def tryTake: F[Option[A]]

  /**
   * Attempts to dequeue elements from the front of the queue, if they are available without
   * semantically blocking. This is a convenience method that recursively runs `tryTake`. It
   * does not provide any additional performance benefits.
   *
   * @param maxN
   *   The max elements to dequeue. Passing `None` will try to dequeue the whole queue.
   *
   * @return
   *   an effect that describes whether the dequeueing of elements from the queue succeeded
   *   without blocking, with `None` denoting that no element was available
   */
  def tryTakeN(maxN: Option[Int])(implicit F: Monad[F]): F[Option[List[A]]] = {
    QueueSource.assertMaxNPositive(maxN)
    F.tailRecM[(Option[List[A]], Int), Option[List[A]]](
      (None, 0)
    ) {
      case (list, i) =>
        if (maxN.contains(i)) list.map(_.reverse).asRight.pure[F]
        else {
          tryTake.map {
            case None => list.map(_.reverse).asRight
            case Some(x) =>
              if (list.isEmpty) (Some(List(x)), i + 1).asLeft
              else (list.map(x +: _), i + 1).asLeft
          }
        }
    }
  }

  def size: F[Int]
}

object QueueSource {
  private def assertMaxNPositive(maxN: Option[Int]): Unit = maxN match {
    case Some(n) if n <= 0 =>
      throw new IllegalArgumentException(s"Provided maxN parameter must be positive, was $n")
    case _ => ()
  }

  implicit def catsFunctorForQueueSource[F[_]: Functor]: Functor[QueueSource[F, *]] =
    new Functor[QueueSource[F, *]] {
      override def map[A, B](fa: QueueSource[F, A])(f: A => B): QueueSource[F, B] =
        new QueueSource[F, B] {
          override def take: F[B] =
            fa.take.map(f)
          override def tryTake: F[Option[B]] = {
            fa.tryTake.map(_.map(f))
          }
          override def size: F[Int] =
            fa.size
        }
    }
}

trait QueueSink[F[_], A] {

  /**
   * Enqueues the given element at the back of the queue, possibly semantically blocking until
   * sufficient capacity becomes available.
   *
   * @param a
   *   the element to be put at the back of the queue
   */
  def offer(a: A): F[Unit]

  /**
   * Attempts to enqueue the given element at the back of the queue without semantically
   * blocking.
   *
   * @param a
   *   the element to be put at the back of the queue
   * @return
   *   an effect that describes whether the enqueuing of the given element succeeded without
   *   blocking
   */
  def tryOffer(a: A): F[Boolean]

  /**
   * Attempts to enqueue the given elements at the back of the queue without semantically
   * blocking. If an item in the list cannot be enqueued, the remaining elements will be
   * returned. This is a convenience method that recursively runs `tryOffer` and does not offer
   * any additional performance benefits.
   *
   * @param list
   *   the elements to be put at the back of the queue
   * @return
   *   an effect that contains the remaining valus that could not be offered.
   */
  def tryOfferN(list: List[A])(implicit F: Monad[F]): F[List[A]] = list match {
    case Nil => F.pure(list)
    case h :: t =>
      tryOffer(h).ifM(
        tryOfferN(t),
        F.pure(list)
      )
  }
}

object QueueSink {
  implicit def catsContravariantForQueueSink[F[_]]: Contravariant[QueueSink[F, *]] =
    new Contravariant[QueueSink[F, *]] {
      override def contramap[A, B](fa: QueueSink[F, A])(f: B => A): QueueSink[F, B] =
        new QueueSink[F, B] {
          override def offer(b: B): F[Unit] =
            fa.offer(f(b))
          override def tryOffer(b: B): F[Boolean] =
            fa.tryOffer(f(b))
        }
    }
}
