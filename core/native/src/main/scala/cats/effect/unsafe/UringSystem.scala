/*
 * Copyright 2020-2025 Typelevel
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
package unsafe

import cats.effect.kernel.{Cont, MonadCancelThrow}
import cats.effect.std.Mutex
import cats.effect.unsafe.metrics.PollerMetrics
import cats.syntax.all._
import cats.~>

import org.typelevel.scalaccompat.annotation._

import scala.collection.mutable.LongMap
import scala.scalanative.libc.stdlib
import scala.scalanative.posix.errno._
import scala.scalanative.posix.string._
import scala.scalanative.posix.unistd
import scala.scalanative.unsafe._
import scala.scalanative.unsigned._

import java.io.IOException
import java.util.BitSet
import java.util.concurrent.ConcurrentLinkedDeque

object UringSystem extends PollingSystem {

  import liburing._
  import liburingOps._

  private[this] final val MaxEvents = WorkStealingThreadPoolConstants.PollingTicks

  type Api = Uring

  def close(): Unit = ()

  def makeApi(ctx: PollingContext[Poller]): Api = new UringApi(ctx)

  def makePoller(): Poller = {
    val ring = stdlib.malloc(sizeof[io_uring]).asInstanceOf[Ptr[io_uring]]
    if (ring == null)
      throw new IOException(fromCString(strerror(errno)))

    val queueInitFlags =
      IORING_SETUP_SUBMIT_ALL | IORING_SETUP_COOP_TASKRUN | IORING_SETUP_TASKRUN_FLAG
    val ret = io_uring_queue_init(MaxEvents.toUInt, ring, queueInitFlags.toUInt)
    if (ret < 0) {
      stdlib.free(ring.asInstanceOf[Ptr[Byte]])
      throw new IOException(fromCString(strerror(-ret)))
    }

    val wakeupFdFlags = eventfd.EFD_NONBLOCK | eventfd.EFD_CLOEXEC
    val wakeupFd = eventfd.eventfd(0, wakeupFdFlags)
    if (wakeupFd == -1) {
      val msg = fromCString(strerror(errno))
      io_uring_queue_exit(ring)
      stdlib.free(ring.asInstanceOf[Ptr[Byte]])
      throw new IOException(msg)
    }

    new Poller(ring, wakeupFd)
  }

  def closePoller(poller: Poller): Unit = poller.close()

  def poll(poller: Poller, nanos: Long): PollResult =
    poller.poll(nanos)

  def processReadyEvents(poller: Poller): Boolean =
    poller.processReadyEvents()

  def needsPoll(poller: Poller): Boolean = poller.needsPoll()

  def interrupt(targetThread: Thread, targetPoller: Poller): Unit =
    targetPoller.wakeup()

  def metrics(poller: Poller): PollerMetrics = poller.metrics()

  abstract class Uring private[UringSystem] () {
    def call(prep: Ptr[io_uring_sqe] => Unit, checkErrors: Boolean = true): IO[Int]

    def bracket(prep: Ptr[io_uring_sqe] => Unit, checkErrors: Boolean = true)(
        release: Int => IO[Unit]): Resource[IO, Int]
  }

  object Uring {
    def get: IO[Uring] =
      IO.pollers.flatMap {
        _.collectFirst { case ring: Uring => ring }
          .liftTo[IO](new RuntimeException("No UringSystem installed"))
      }
  }

  private final class UringApi(ctx: PollingContext[Poller])
      extends Uring
      with FileDescriptorPoller {
    private[this] val noopRelease: Int => IO[Unit] = _ => IO.unit

    def call(prep: Ptr[io_uring_sqe] => Unit, checkErrors: Boolean = true): IO[Int] =
      exec(prep, checkErrors)(noopRelease)

    def bracket(prep: Ptr[io_uring_sqe] => Unit, checkErrors: Boolean = true)(
        release: Int => IO[Unit]): Resource[IO, Int] = {
      val releaseSuccessful: Int => IO[Unit] = result =>
        if (result >= 0) release(result) else IO.unit

      Resource.makeFull[IO, Int] { poll => poll(exec(prep, checkErrors)(releaseSuccessful)) }(
        releaseSuccessful)
    }

    private def exec(prep: Ptr[io_uring_sqe] => Unit, checkErrors: Boolean)(
        release: Int => IO[Unit]
    ): IO[Int] =
      IO.cont {
        new Cont[IO, Int, Int] {
          def apply[F[_]](
              implicit F: MonadCancelThrow[F]
          ): (Either[Throwable, Int] => Unit, F[Int], IO ~> F) => F[Int] = {
            (resume, get, lift) =>
              F.uncancelable { poll =>
                val submit = IO.async_[(__u64, Poller)] { cb =>
                  ctx.accessPoller { poller =>
                    val sqe = poller.getSqe(resume)
                    prep(sqe)
                    poller.trackSubmission(sqe)
                    cb(Right((sqe.user_data, poller)))
                  }
                }

                lift(submit)
                  .flatMap {
                    case (addr, submittedPoller) =>
                      F.onCancel(
                        poll(get),
                        lift(cancel(addr, submittedPoller)).ifM(
                          F.unit,
                          // If cannot cancel, fallback to get
                          get.flatMap { rtn =>
                            if (checkErrors && rtn < 0)
                              F.raiseError(new IOException(fromCString(strerror(-rtn))))
                            else lift(release(rtn))
                          }
                        )
                      )
                  }
                  .flatTap { e =>
                    F.raiseWhen(checkErrors && e < 0)(
                      new IOException(fromCString(strerror(-e))))
                  }
              }
          }
        }
      }

    private[this] def cancel(addr: __u64, submittedPoller: Poller): IO[Boolean] =
      IO.async_[Int] { cb =>
        ctx.accessPoller { currentPoller =>
          if (currentPoller eq submittedPoller) {
            val sqe = currentPoller.getSqe(cb)
            io_uring_prep_cancel64(sqe, addr, 0)
          } else {
            submittedPoller.enqueueCancelOperation(addr, cb)
          }
        }
      }.map(_ == 0)

    def registerFileDescriptor(
        fd: Int,
        reads: Boolean,
        writes: Boolean
    ): Resource[IO, FileDescriptorPollHandle] =
      Resource.eval {
        (Mutex[IO], Mutex[IO]).mapN { (readMutex, writeMutex) =>
          new FileDescriptorPollHandle {
            def pollReadRec[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
              readMutex.lock.surround {
                a.tailRecM { a =>
                  f(a).flatTap { r =>
                    if (r.isRight) IO.unit
                    else call(io_uring_prep_poll_add(_, fd, POLLIN.toUInt)).void
                  }
                }
              }

            def pollWriteRec[A, B](a: A)(f: A => IO[Either[A, B]]): IO[B] =
              writeMutex.lock.surround {
                a.tailRecM { a =>
                  f(a).flatTap { r =>
                    if (r.isRight) IO.unit
                    else call(io_uring_prep_poll_add(_, fd, POLLOUT.toUInt)).void
                  }
                }
              }
          }
        }
      }
  }

  final class Poller private[UringSystem] (
      ring: Ptr[io_uring],
      wakeupFd: CInt
  ) extends PollerMetrics {
    private[this] var pendingSubmissions: Boolean = false
    private[this] var listeningWakeup: Boolean = false

    private[this] val ids: BitSet = {
      val bs = new BitSet(Short.MaxValue)
      bs.set(0) // reserve id 0 for the wakeup poll_add
      bs
    }

    private[this] val callbacks: LongMap[Either[Throwable, Int] => Unit] =
      LongMap.empty

    private[this] val opCategories: LongMap[Int] = LongMap.empty

    private[this] val cancelOperations
        : ConcurrentLinkedDeque[(__u64, Either[Throwable, Int] => Unit)] =
      new ConcurrentLinkedDeque

    private[this] val cqesArray: Array[Byte] = new Array[Byte](MaxEvents * 8)
    @inline private[this] def cqesPtr: Ptr[Ptr[io_uring_cqe]] =
      cqesArray.atUnsafe(0).asInstanceOf[Ptr[Ptr[io_uring_cqe]]]

    private[this] var totalReadSubmitted = 0L
    private[this] var totalReadSucceeded = 0L
    private[this] var totalReadErrored = 0L
    private[this] var totalReadCanceled = 0L
    private[this] var readOutstanding = 0

    private[this] var totalWriteSubmitted = 0L
    private[this] var totalWriteSucceeded = 0L
    private[this] var totalWriteErrored = 0L
    private[this] var totalWriteCanceled = 0L
    private[this] var writeOutstanding = 0

    private[this] var totalAcceptSubmitted = 0L
    private[this] var totalAcceptSucceeded = 0L
    private[this] var totalAcceptErrored = 0L
    private[this] var totalAcceptCanceled = 0L
    private[this] var acceptOutstanding = 0

    private[this] var totalConnectSubmitted = 0L
    private[this] var totalConnectSucceeded = 0L
    private[this] var totalConnectErrored = 0L
    private[this] var totalConnectCanceled = 0L
    private[this] var connectOutstanding = 0

    override def operationsOutstandingCount(): Int =
      readOutstanding + writeOutstanding + acceptOutstanding + connectOutstanding
    override def totalOperationsSubmittedCount(): Long =
      totalReadSubmitted + totalWriteSubmitted + totalAcceptSubmitted + totalConnectSubmitted
    override def totalOperationsSucceededCount(): Long =
      totalReadSucceeded + totalWriteSucceeded + totalAcceptSucceeded + totalConnectSucceeded
    override def totalOperationsErroredCount(): Long =
      totalReadErrored + totalWriteErrored + totalAcceptErrored + totalConnectErrored
    override def totalOperationsCanceledCount(): Long =
      totalReadCanceled + totalWriteCanceled + totalAcceptCanceled + totalConnectCanceled
    override def acceptOperationsOutstandingCount(): Int = acceptOutstanding
    override def totalAcceptOperationsSubmittedCount(): Long = totalAcceptSubmitted
    override def totalAcceptOperationsSucceededCount(): Long = totalAcceptSucceeded
    override def totalAcceptOperationsErroredCount(): Long = totalAcceptErrored
    override def totalAcceptOperationsCanceledCount(): Long = totalAcceptCanceled
    override def connectOperationsOutstandingCount(): Int = connectOutstanding
    override def totalConnectOperationsSubmittedCount(): Long = totalConnectSubmitted
    override def totalConnectOperationsSucceededCount(): Long = totalConnectSucceeded
    override def totalConnectOperationsErroredCount(): Long = totalConnectErrored
    override def totalConnectOperationsCanceledCount(): Long = totalConnectCanceled
    override def readOperationsOutstandingCount(): Int = readOutstanding
    override def totalReadOperationsSubmittedCount(): Long = totalReadSubmitted
    override def totalReadOperationsSucceededCount(): Long = totalReadSucceeded
    override def totalReadOperationsErroredCount(): Long = totalReadErrored
    override def totalReadOperationsCanceledCount(): Long = totalReadCanceled
    override def writeOperationsOutstandingCount(): Int = writeOutstanding
    override def totalWriteOperationsSubmittedCount(): Long = totalWriteSubmitted
    override def totalWriteOperationsSucceededCount(): Long = totalWriteSucceeded
    override def totalWriteOperationsErroredCount(): Long = totalWriteErrored
    override def totalWriteOperationsCanceledCount(): Long = totalWriteCanceled

    override def toString: String = "Uring"

    private[UringSystem] def metrics(): PollerMetrics = this

    private[this] def nextId(): Long = {
      val id = ids.nextClearBit(1)
      if (id >= Short.MaxValue)
        throw new IOException("io_uring callback id space exhausted")
      ids.set(id)
      id.toLong
    }

    private[UringSystem] def getSqe(
        cb: Either[Throwable, Int] => Unit
    ): Ptr[io_uring_sqe] = {
      pendingSubmissions = true
      val sqe = io_uring_get_sqe(ring)
      val id = nextId()
      callbacks.put(id, cb)
      sqe.user_data = id.toULong
      sqe
    }

    private[UringSystem] def trackSubmission(sqe: Ptr[io_uring_sqe]): Unit = {
      val category = categorize(sqe)
      if (category != CategoryUntracked) {
        opCategories.put(sqe.user_data.toLong, category)
        incrementSubmission(category)
      }
    }

    private[this] def categorize(sqe: Ptr[io_uring_sqe]): Int = {
      val opcode = sqe.opcode.toInt
      opcode match {
        case OpRead | OpReadv | OpReadFixed | OpRecv | OpRecvmsg => CategoryRead
        case OpWrite | OpWritev | OpWriteFixed | OpSend | OpSendmsg => CategoryWrite
        case OpAccept => CategoryAccept
        case OpConnect => CategoryConnect
        case IORING_OP_POLL_ADD =>
          val mask = sqe.poll32_events.toInt
          if ((mask & POLLIN) != 0) CategoryRead
          else if ((mask & POLLOUT) != 0) CategoryWrite
          else CategoryUntracked
        case _ => CategoryUntracked
      }
    }

    private[this] def incrementSubmission(category: Int): Unit = category match {
      case CategoryRead =>
        totalReadSubmitted += 1
        readOutstanding += 1
      case CategoryWrite =>
        totalWriteSubmitted += 1
        writeOutstanding += 1
      case CategoryAccept =>
        totalAcceptSubmitted += 1
        acceptOutstanding += 1
      case CategoryConnect =>
        totalConnectSubmitted += 1
        connectOutstanding += 1
      case _ => ()
    }

    private[this] def handleCompletion(category: Int, res: Int): Unit = {
      val canceled = res == -ECANCELED
      val succeeded = res >= 0
      category match {
        case CategoryRead =>
          readOutstanding -= 1
          if (canceled) totalReadCanceled += 1
          else if (succeeded) totalReadSucceeded += 1
          else totalReadErrored += 1
        case CategoryWrite =>
          writeOutstanding -= 1
          if (canceled) totalWriteCanceled += 1
          else if (succeeded) totalWriteSucceeded += 1
          else totalWriteErrored += 1
        case CategoryAccept =>
          acceptOutstanding -= 1
          if (canceled) totalAcceptCanceled += 1
          else if (succeeded) totalAcceptSucceeded += 1
          else totalAcceptErrored += 1
        case CategoryConnect =>
          connectOutstanding -= 1
          if (canceled) totalConnectCanceled += 1
          else if (succeeded) totalConnectSucceeded += 1
          else totalConnectErrored += 1
        case _ => ()
      }
    }

    private[UringSystem] def enqueueCancelOperation(
        addr: __u64,
        cb: Either[Throwable, Int] => Unit
    ): Unit = {
      cancelOperations.add((addr, cb))
      wakeup()
    }

    private[UringSystem] def wakeup(): Unit = {
      val buf = stackalloc[ULong]()
      !buf = 1.toULong
      val rtn = unistd.write(wakeupFd, buf, sizeof[ULong])
      if (rtn < 0 && errno != EAGAIN)
        throw new IOException(fromCString(strerror(errno)))
    }

    private[this] def armWakeup(): Unit = {
      val sqe = io_uring_get_sqe(ring)
      sqe.user_data = 0L.toULong
      io_uring_prep_poll_add(sqe, wakeupFd, POLLIN.toUInt)
      listeningWakeup = true
    }

    private[this] def drainCancelQueue(): Unit = {
      var op = cancelOperations.poll()
      while (op ne null) {
        val sqe = getSqe(op._2)
        io_uring_prep_cancel64(sqe, op._1, 0)
        op = cancelOperations.poll()
      }
    }

    private[UringSystem] def close(): Unit = {
      io_uring_queue_exit(ring)
      stdlib.free(ring.asInstanceOf[Ptr[Byte]])
      unistd.close(wakeupFd)
      ()
    }

    private[UringSystem] def needsPoll(): Boolean =
      pendingSubmissions || callbacks.nonEmpty || !cancelOperations.isEmpty()

    private[UringSystem] def poll(nanos: Long): PollResult = {

      if (!listeningWakeup) armWakeup()
      drainCancelQueue()

      if (nanos == 0) {
        if (pendingSubmissions) {
          var rtn = io_uring_submit(ring)
          while (rtn == -EBUSY) {
            processReadyEvents()
            rtn = io_uring_submit(ring)
          }
          pendingSubmissions = false
        }
      } else {
        val timeoutSpec =
          if (nanos == -1) null
          else {
            val ts = stackalloc[__kernel_timespec]()
            ts.tv_sec = nanos / 1000000000L
            ts.tv_nsec = nanos % 1000000000L
            ts
          }

        val cqe = stackalloc[Ptr[io_uring_cqe]]()
        if (pendingSubmissions) {
          var rtn =
            io_uring_submit_and_wait_timeout(ring, cqe, 1.toUInt, timeoutSpec, null)
          while (rtn == -EBUSY) {
            processReadyEvents()
            rtn = io_uring_submit(ring)
          }
        } else {
          io_uring_wait_cqe_timeout(ring, cqe, timeoutSpec)
        }
        pendingSubmissions = false
      }

      val filledCount = io_uring_peek_batch_cqe(ring, cqesPtr, MaxEvents.toUInt).toInt

      if (filledCount > 0) {
        if (filledCount < MaxEvents) PollResult.Complete else PollResult.Incomplete
      } else PollResult.Interrupted
    }

    private[UringSystem] def processReadyEvents(): Boolean = {
      val cqes = cqesPtr
      val filledCount = io_uring_peek_batch_cqe(ring, cqes, MaxEvents.toUInt).toInt

      var i = 0
      while (i < filledCount) {
        val cqe = !(cqes + i.toLong)
        val id = cqe.user_data.toLong
        if (id == 0L) {
          val buf = stackalloc[ULong]()
          val rtn = unistd.read(wakeupFd, buf, sizeof[ULong])
          if (rtn < 0 && errno != EAGAIN)
            throw new IOException(fromCString(strerror(errno)))
          listeningWakeup = false
        } else {
          val cb = callbacks.remove(id)
          val category = opCategories.remove(id).getOrElse(CategoryUntracked)
          ids.clear(id.toInt)
          val res = cqe.res
          handleCompletion(category, res)
          if (cb.isDefined) cb.get(Right(res))
        }
        i += 1
      }

      io_uring_cq_advance(ring, filledCount.toUInt)
      filledCount > 0
    }
  }

  private final val POLLIN: Int = 0x001
  private final val POLLOUT: Int = 0x004

  private final val OpReadv = 1
  private final val OpWritev = 2
  private final val OpReadFixed = 4
  private final val OpWriteFixed = 5
  private final val OpSendmsg = 9
  private final val OpRecvmsg = 10
  private final val OpAccept = 13
  private final val OpConnect = 16
  private final val OpRead = 22
  private final val OpWrite = 23
  private final val OpSend = 26
  private final val OpRecv = 27

  private final val CategoryUntracked = 0
  private final val CategoryRead = 1
  private final val CategoryWrite = 2
  private final val CategoryAccept = 3
  private final val CategoryConnect = 4

  @nowarn212
  @extern
  private object eventfd {
    final val EFD_CLOEXEC =
      0x80000 // Do not leak the wakeup descriptor into programs launched with execve (i.e new ProcessBuilder("git", "status").start()).
    final val EFD_NONBLOCK =
      0x00800 // Return EAGAIN instead of blocking on an empty read or an overflowing write.

    def eventfd(initval: Int, flags: Int): Int =
      extern
  }

  @nowarn212
  @link("uring")
  @define("CATS_EFFECT_URING")
  @extern
  private[unsafe] object liburing {

    final val IORING_SETUP_SUBMIT_ALL = 1 << 7
    final val IORING_SETUP_COOP_TASKRUN = 1 << 8
    final val IORING_SETUP_TASKRUN_FLAG = 1 << 9
    final val IORING_SETUP_SINGLE_ISSUER = 1 << 12
    final val IORING_SETUP_DEFER_TASKRUN = 1 << 13

    final val IORING_OP_NOP = 0
    final val IORING_OP_POLL_ADD = 6
    final val IORING_OP_ASYNC_CANCEL = 14

    type __u8 = CUnsignedChar
    type __u16 = CUnsignedShort
    type __s32 = CInt
    type __u32 = CUnsignedInt
    type __u64 = CUnsignedLongLong

    type __kernel_time64_t = CLongLong
    type __kernel_timespec = CStruct2[__kernel_time64_t, CLongLong]

    type __kernel_rwf_t = CUnsignedInt

    type io_uring = CStruct9[
      io_uring_sq,
      io_uring_cq,
      CUnsignedInt,
      CInt,
      CUnsignedInt,
      CInt,
      __u8,
      CArray[__u8, Nat._3],
      CUnsignedInt
    ]

    type io_uring_cq = CStruct12[
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[io_uring_cqe],
      CSize,
      Ptr[Byte],
      CUnsignedInt,
      CUnsignedInt,
      CArray[CUnsignedInt, Nat._2]
    ]

    type io_uring_cqe = CStruct3[__u64, __s32, __u32]

    type io_uring_sq = CStruct15[
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[CUnsignedInt],
      Ptr[io_uring_sqe],
      CUnsignedInt,
      CUnsignedInt,
      CSize,
      Ptr[Byte],
      CUnsignedInt,
      CUnsignedInt,
      CArray[CUnsignedInt, Nat._2]
    ]

    type io_uring_sqe = CStruct10[
      __u8,
      __u8,
      __u16,
      __s32,
      __u64,
      __u64,
      __u32,
      __u32,
      __u64,
      CArray[__u64, Nat._3]
    ]

    def io_uring_queue_init(
        entries: CUnsignedInt,
        ring: Ptr[io_uring],
        flags: CUnsignedInt
    ): CInt = extern

    def io_uring_queue_exit(ring: Ptr[io_uring]): Unit = extern

    @name("ce_io_uring_get_sqe")
    def io_uring_get_sqe(ring: Ptr[io_uring]): Ptr[io_uring_sqe] = extern

    def io_uring_submit(ring: Ptr[io_uring]): CInt = extern

    @blocking
    def io_uring_submit_and_wait_timeout(
        ring: Ptr[io_uring],
        cqe_ptr: Ptr[Ptr[io_uring_cqe]],
        wait_nr: CUnsignedInt,
        ts: Ptr[__kernel_timespec],
        sigmask: Ptr[Byte]
    ): CInt = extern

    @blocking
    def io_uring_wait_cqe_timeout(
        ring: Ptr[io_uring],
        cqe_ptr: Ptr[Ptr[io_uring_cqe]],
        ts: Ptr[__kernel_timespec]
    ): CInt = extern

    def io_uring_peek_batch_cqe(
        ring: Ptr[io_uring],
        cqes: Ptr[Ptr[io_uring_cqe]],
        count: CUnsignedInt
    ): CUnsignedInt = extern

    @name("ce_io_uring_cq_advance")
    def io_uring_cq_advance(ring: Ptr[io_uring], nr: CUnsignedInt): Unit = extern
  }

  private[unsafe] object liburingOps {

    import liburing._

    def io_uring_prep_rw(
        op: Int,
        sqe: Ptr[io_uring_sqe],
        fd: Int,
        addr: Ptr[?],
        len: CUnsignedInt,
        offset: __u64
    ): Unit = {
      sqe.opcode = op.toUByte
      sqe.flags = 0.toUByte
      sqe.ioprio = 0.toUShort
      sqe.fd = fd
      sqe.off = offset
      sqe.addr = if (addr eq null) 0.toULong else addr.toLong.toULong
      sqe.len = len
      sqe.rw_flags = 0.toUInt
      sqe.__pad2(0) = 0.toULong
      sqe.__pad2(1) = 0.toULong
      sqe.__pad2(2) = 0.toULong
    }

    def io_uring_prep_nop(sqe: Ptr[io_uring_sqe]): Unit =
      io_uring_prep_rw(IORING_OP_NOP, sqe, -1, null, 0.toUInt, 0.toULong)

    def io_uring_prep_cancel64(
        sqe: Ptr[io_uring_sqe],
        user_data: __u64,
        flags: CInt
    ): Unit = {
      io_uring_prep_rw(IORING_OP_ASYNC_CANCEL, sqe, -1, null, 0.toUInt, 0.toULong)
      sqe.addr = user_data
      sqe.cancel_flags = flags.toUInt
    }

    def io_uring_prep_poll_add(
        sqe: Ptr[io_uring_sqe],
        fd: CInt,
        poll_mask: CUnsignedInt
    ): Unit = {
      io_uring_prep_rw(IORING_OP_POLL_ADD, sqe, fd, null, 0.toUInt, 0.toULong)
      sqe.poll32_events = poll_mask
    }

    implicit final class io_uring_sqeOps(val io_uring_sqe: Ptr[io_uring_sqe]) extends AnyVal {
      def opcode: __u8 = io_uring_sqe._1
      def opcode_=(opcode: __u8): Unit = !io_uring_sqe.at1 = opcode

      def flags: __u8 = io_uring_sqe._2
      def flags_=(flags: __u8): Unit = !io_uring_sqe.at2 = flags

      def ioprio: __u16 = io_uring_sqe._3
      def ioprio_=(ioprio: __u16): Unit = !io_uring_sqe.at3 = ioprio

      def fd: __s32 = io_uring_sqe._4
      def fd_=(fd: __s32): Unit = !io_uring_sqe.at4 = fd

      def off: __u64 = io_uring_sqe._5
      def off_=(off: __u64): Unit = !io_uring_sqe.at5 = off

      def addr: __u64 = io_uring_sqe._6
      def addr_=(addr: __u64): Unit = !io_uring_sqe.at6 = addr

      def len: __u32 = io_uring_sqe._7
      def len_=(len: __u32): Unit = !io_uring_sqe.at7 = len

      def rw_flags: __kernel_rwf_t = io_uring_sqe._8
      def rw_flags_=(rw_flags: __kernel_rwf_t): Unit = !io_uring_sqe.at8 = rw_flags

      def poll32_events: __u32 = io_uring_sqe._8
      def poll32_events_=(poll32_events: __u32): Unit = !io_uring_sqe.at8 = poll32_events

      def msg_flags: __u32 = io_uring_sqe._8
      def msg_flags_=(msg_flags: __u32): Unit = !io_uring_sqe.at8 = msg_flags

      def accept_flags: __u32 = io_uring_sqe._8
      def accept_flags_=(accept_flags: __u32): Unit = !io_uring_sqe.at8 = accept_flags

      def cancel_flags: __u32 = io_uring_sqe._8
      def cancel_flags_=(cancel_flags: __u32): Unit = !io_uring_sqe.at8 = cancel_flags

      def user_data: __u64 = io_uring_sqe._9
      def user_data_=(user_data: __u64): Unit = !io_uring_sqe.at9 = user_data

      def __pad2: CArray[__u64, Nat._3] = io_uring_sqe._10
    }

    implicit final class io_uring_cqeOps(val io_uring_cqe: Ptr[io_uring_cqe]) extends AnyVal {
      def user_data: __u64 = io_uring_cqe._1
      def user_data_=(user_data: __u64): Unit = !io_uring_cqe.at1 = user_data

      def res: __s32 = io_uring_cqe._2
      def res_=(res: __s32): Unit = !io_uring_cqe.at2 = res

      def flags: __u32 = io_uring_cqe._3
      def flags_=(flags: __u32): Unit = !io_uring_cqe.at3 = flags
    }

    implicit final class __kernel_timespecOps(val ts: Ptr[__kernel_timespec]) extends AnyVal {
      def tv_sec: __kernel_time64_t = ts._1
      def tv_sec_=(v: __kernel_time64_t): Unit = !ts.at1 = v

      def tv_nsec: CLongLong = ts._2
      def tv_nsec_=(v: CLongLong): Unit = !ts.at2 = v
    }
  }
}
