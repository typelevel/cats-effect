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

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong, AtomicLongArray, AtomicReference}

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
      case f0: Async[F] =>
        if (capacity > 1) // jctools implementation cannot handle size = 1
          boundedForAsync[F, A](capacity)(f0)
        else
          boundedForConcurrent[F, A](capacity)

      case _ =>
        boundedForConcurrent[F, A](capacity)
    }

  private[effect] def boundedForConcurrent[F[_], A](capacity: Int)(
      implicit F: GenConcurrent[F, _]): F[Queue[F, A]] = {
    assertNonNegative(capacity)
    F.ref(State.empty[F, A]).map(new BoundedQueue(capacity, _))
  }

  private[effect] def boundedForAsync[F[_], A](capacity: Int)(
      implicit F: Async[F]): F[Queue[F, A]] = {
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
    boundedForConcurrent(Int.MaxValue) // TODO UnboundedAsyncQueue

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

  private val EitherUnit: Either[Nothing, Unit] = Right(())
  private val FailureSignal: Throwable = new RuntimeException
    with scala.util.control.NoStackTrace

  /*
   * Does not correctly handle bound = 0 because take waiters are async[Unit]
   */
  private final class BoundedAsyncQueue[F[_], A](capacity: Int)(implicit F: Async[F])
      extends Queue[F, A] {
    require(capacity > 1)

    private[this] val buffer = new UnsafeBounded[A](capacity)

    // private[this] val takers = new UnsafeUnbounded[Either[Throwable, Unit] => Unit]()
    // private[this] val offerers = new UnsafeUnbounded[Either[Throwable, Unit] => Unit]()

    private[this] val takers = new ConcurrentLinkedQueue[AtomicReference[Either[Throwable, Unit] => Unit]]()
    private[this] val offerers = new ConcurrentLinkedQueue[AtomicReference[Either[Throwable, Unit] => Unit]]()

    def offer(a: A): F[Unit] = F defer {
      try {
        buffer.put(a)
        // println(s"offered: size = ${buffer.size()}")
        notifyOne(takers)
        F.unit
      } catch {
        case FailureSignal =>
          var succeeded = false

          val wait = F.async[Unit] { k =>
            F delay {
              val ref = new AtomicReference(k)
              offerers.offer(ref)

              try {
                buffer.put(a)
                ref.set(null)
                succeeded = true
                k(EitherUnit)
                notifyOne(takers)

                None
              } catch {
                case FailureSignal =>
                  // println(s"failed offer size = ${buffer.size()}")
                  Some(F.delay(ref.set(null)))
              }
            }
          }

          wait *> F.defer(if (succeeded) F.unit else offer(a))
      }
    }

    def tryOffer(a: A): F[Boolean] = F delay {
      try {
        buffer.put(a)
        notifyOne(takers)
        true
      } catch {
        case FailureSignal =>
          false
      }
    }

    val size: F[Int] = F.delay(buffer.size())

    val take: F[A] = F defer {
      try {
        val result = buffer.take()
        // println(s"took: size = ${buffer.size()}")
        notifyOne(offerers)
        F.pure(result)
      } catch {
        case FailureSignal =>
          var received = false
          var result: A = null.asInstanceOf[A]

          val wait = F.async[Unit] { k =>
            F delay {
              val ref = new AtomicReference(k)
              takers.offer(ref)

              try {
                result = buffer.take()
                ref.set(null)
                received = true
                k(EitherUnit)
                notifyOne(offerers)

                None
              } catch {
                case FailureSignal =>
                  // println(s"failed take size = ${buffer.size()}")
                  Some(F.delay(ref.set(null)))
              }
            }
          }

          wait *> F.defer(if (received) F.pure(result) else take)
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

    def debug(): Unit = {
      println(s"buffer: ${buffer.debug()}")
      // println(s"takers: ${takers.debug()}")
      // println(s"offerers: ${offerers.debug()}")
    }

    // TODO could optimize notifications by checking if buffer is completely empty on put
    @tailrec
    private[this] def notifyOne(
        waiters: ConcurrentLinkedQueue[AtomicReference[Either[Throwable, Unit] => Unit]]): Unit = {
      val ref = waiters.poll()
      if (ref != null) {
        val f = ref.get()
        if (f != null) {
          f(EitherUnit)
        } else {
          notifyOne(waiters)
        }
      }
    }
  }

  // ported with love from https://github.com/JCTools/JCTools/blob/master/jctools-core/src/main/java/org/jctools/queues/MpmcArrayQueue.java
  private[effect] final class UnsafeBounded[A](bound: Int) {
    require(bound > 1)

    private[this] val buffer = new Array[AnyRef](bound)

    private[this] val sequenceBuffer = new AtomicLongArray(bound)
    private[this] val head = new AtomicLong(0)
    private[this] val tail = new AtomicLong(0)

    0.until(bound).foreach(i => sequenceBuffer.set(i, i.toLong))

    private[this] val length = new AtomicInteger(0)

    def debug(): String = buffer.mkString("[", ", ", "]")

    def size(): Int = length.get()

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
      length.incrementAndGet()

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
      length.decrementAndGet()

      back
    }

    // TODO handle wraparound negative
    private[this] def project(idx: Long): Int =
      (idx % bound).toInt
  }

  // TODO we don't allow null values, which is kinda fine for now but not fine if we use this to back Queue.unbounded
  final class UnsafeUnbounded[A] {
    // YMMV. HERE BE DRAGONS. NEEDS HARDENING. NO WARRANTY. CODED BY A SLEEPDEPRIVED NUTJOB.
    private[this] val first = new AtomicReference[Cell]
    private[this] val last = new AtomicReference[Cell]

    def put(data: A): () => Unit = {
      val cell = new Cell(data)

      last.getAndSet(cell) match {
        case null => first.set(cell) // This is safe since only one thread will return null for last.getAndSet and consumers will consume from first, and first will be null until this line
        case prevLast => prevLast.set(cell) // order the update to the next-to-last
      }

      cell
    }

    @tailrec
    def take(): A = {
      val taken = first.get()

      if (taken == null) {
        if (last.get() != null) take() // Waiting for prevLast.set(cell)
        else throw FailureSignal
      } else {
        val next = taken.get()

        if (first.compareAndSet(taken, next)) {
          val ret = taken.data()
          taken()

          if (next == null) {
            last.compareAndSet(taken, null)
          }

          if (ret != null) {
            ret
          } else {
            take()
          }
        } else {
          take()
        }
      }
    }

    def debug(): String = {
      val f = first.get()

      if (f == null) {
        "[]"
      } else {
        f.debug()
      }
    }

    private final class Cell(private[this] final var _data: A)
        extends AtomicReference[Cell]
        with (() => Unit) {

      def data(): A = _data

      final override def apply(): Unit = {
        _data = null.asInstanceOf[A] // You want a lazySet here
      }

      def debug(): String = {
        val tail = get()
        s"${_data} -> ${if (tail == null) "[]" else tail.debug()}"
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
