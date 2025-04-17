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

import cats.effect.unsafe.metrics.PollerMetrics

import java.nio.channels.{SelectableChannel, SelectionKey}
import java.nio.channels.spi.{AbstractSelector, SelectorProvider}
import java.util.Iterator

import SelectorSystem._

final class SelectorSystem private (provider: SelectorProvider) extends PollingSystem {

  type Api = Selector

  def close(): Unit = ()

  def makeApi(ctx: PollingContext[Poller]): Selector =
    new SelectorImpl(ctx, provider)

  def makePoller(): Poller = new Poller(provider.openSelector())

  def closePoller(poller: Poller): Unit =
    poller.selector.close()

  def poll(poller: Poller, nanos: Long): PollResult = {
    val millis = if (nanos >= 0) nanos / 1000000 else -1
    val selector = poller.selector

    if (millis == 0) selector.selectNow()
    else if (millis > 0) selector.select(millis)
    else selector.select()

    // closing selector interrupts select
    if (selector.isOpen() && !selector.selectedKeys().isEmpty())
      PollResult.Complete
    else
      PollResult.Interrupted
  }

  def processReadyEvents(poller: Poller): Boolean = {
    val selector = poller.selector
    var fibersRescheduled = false

    val ready = selector.selectedKeys().iterator()
    while (ready.hasNext()) {
      val key = ready.next()
      ready.remove()

      var readyOps = 0
      var error: Throwable = null
      try {
        readyOps = key.readyOps()
        // reset interest in triggered ops
        key.interestOps(key.interestOps() & ~readyOps)
      } catch {
        case ex if UnsafeNonFatal(ex) =>
          error = ex
          readyOps = -1 // interest all waiters
      }

      val value = if (error ne null) Left(error) else Right(readyOps)

      val callbacks = key.attachment().asInstanceOf[Callbacks]
      val iter = callbacks.iterator()
      while (iter.hasNext()) {
        val node = iter.next()

        if ((node.interest & readyOps) != 0) { // drop this node and execute callback
          node.remove()
          val cb = node.callback
          if (cb != null) {
            cb(value)
            fibersRescheduled = true
            if (error ne null) poller.countSucceededOperation(readyOps)
            else poller.countErroredOperation(node.interest)
          } else {
            poller.countCanceledOperation(node.interest)
          }
        }
      }

      ()
    }

    fibersRescheduled
  }

  def needsPoll(poller: Poller): Boolean =
    !poller.selector.keys().isEmpty()

  def interrupt(targetThread: Thread, targetPoller: Poller): Unit = {
    targetPoller.selector.wakeup()
    ()
  }

  def metrics(poller: Poller): PollerMetrics = poller

  final class SelectorImpl private[SelectorSystem] (
      ctx: PollingContext[Poller],
      val provider: SelectorProvider
  ) extends Selector {

    def select(ch: SelectableChannel, ops: Int): IO[Int] = IO.async { selectCb =>
      IO.async_[Option[IO[Unit]]] { cb =>
        ctx.accessPoller { poller =>
          try {
            val selector = poller.selector
            val key = ch.keyFor(selector)

            poller.countSubmittedOperation(ops)

            val node = if (key eq null) { // not yet registered on this selector
              val cbs = new Callbacks
              ch.register(selector, ops, cbs)
              cbs.append(ops, selectCb)
            } else { // existing key
              // mixin the new interest
              key.interestOps(key.interestOps() | ops)
              val cbs = key.attachment().asInstanceOf[Callbacks]
              cbs.append(ops, selectCb)
            }

            val cancel = IO {
              if (ctx.ownPoller(poller)) {
                poller.countCanceledOperation(ops)
                node.remove()
              } else {
                node.clear()
              }
            }

            cb(Right(Some(cancel)))
          } catch {
            case ex if UnsafeNonFatal(ex) =>
              poller.countErroredOperation(ops)
              cb(Left(ex))
          }
        }
      }
    }

  }

  final class Poller private[SelectorSystem] (
      private[SelectorSystem] val selector: AbstractSelector
  ) extends PollerMetrics {

    private[this] var outstandingOperations: Int = 0
    private[this] var outstandingAccepts: Int = 0
    private[this] var outstandingConnects: Int = 0
    private[this] var outstandingReads: Int = 0
    private[this] var outstandingWrites: Int = 0
    private[this] var submittedOperations: Long = 0
    private[this] var submittedAccepts: Long = 0
    private[this] var submittedConnects: Long = 0
    private[this] var submittedReads: Long = 0
    private[this] var submittedWrites: Long = 0
    private[this] var succeededOperations: Long = 0
    private[this] var succeededAccepts: Long = 0
    private[this] var succeededConnects: Long = 0
    private[this] var succeededReads: Long = 0
    private[this] var succeededWrites: Long = 0
    private[this] var erroredOperations: Long = 0
    private[this] var erroredAccepts: Long = 0
    private[this] var erroredConnects: Long = 0
    private[this] var erroredReads: Long = 0
    private[this] var erroredWrites: Long = 0
    private[this] var canceledOperations: Long = 0
    private[this] var canceledAccepts: Long = 0
    private[this] var canceledConnects: Long = 0
    private[this] var canceledReads: Long = 0
    private[this] var canceledWrites: Long = 0

    def countSubmittedOperation(ops: Int): Unit = {
      outstandingOperations += 1
      submittedOperations += 1
      if (isAccept(ops)) {
        outstandingAccepts += 1
        submittedAccepts += 1
      }
      if (isConnect(ops)) {
        outstandingConnects += 1
        submittedConnects += 1
      }
      if (isRead(ops)) {
        outstandingReads += 1
        submittedReads += 1
      }
      if (isWrite(ops)) {
        outstandingWrites += 1
        submittedWrites += 1
      }
    }

    def countSucceededOperation(ops: Int): Unit = {
      outstandingOperations -= 1
      succeededOperations += 1
      if (isAccept(ops)) {
        outstandingAccepts -= 1
        succeededAccepts += 1
      }
      if (isConnect(ops)) {
        outstandingConnects -= 1
        succeededConnects += 1
      }
      if (isRead(ops)) {
        outstandingReads -= 1
        succeededReads += 1
      }
      if (isWrite(ops)) {
        outstandingWrites -= 1
        succeededWrites += 1
      }
    }

    def countErroredOperation(ops: Int): Unit = {
      outstandingOperations -= 1
      erroredOperations += 1
      if (isAccept(ops)) {
        outstandingAccepts -= 1
        erroredAccepts += 1
      }
      if (isConnect(ops)) {
        outstandingConnects -= 1
        erroredConnects += 1
      }
      if (isRead(ops)) {
        outstandingReads -= 1
        erroredReads += 1
      }
      if (isWrite(ops)) {
        outstandingWrites -= 1
        erroredWrites += 1
      }
    }

    def countCanceledOperation(ops: Int): Unit = {
      outstandingOperations -= 1
      canceledOperations += 1
      if (isAccept(ops)) {
        outstandingAccepts -= 1
        canceledAccepts += 1
      }
      if (isConnect(ops)) {
        outstandingConnects -= 1
        canceledConnects += 1
      }
      if (isRead(ops)) {
        outstandingReads -= 1
        canceledReads += 1
      }
      if (isWrite(ops)) {
        outstandingWrites -= 1
        canceledWrites += 1
      }
    }

    private[this] def isAccept(ops: Int): Boolean = (ops & SelectionKey.OP_ACCEPT) != 0
    private[this] def isConnect(ops: Int): Boolean = (ops & SelectionKey.OP_CONNECT) != 0
    private[this] def isRead(ops: Int): Boolean = (ops & SelectionKey.OP_READ) != 0
    private[this] def isWrite(ops: Int): Boolean = (ops & SelectionKey.OP_WRITE) != 0

    def operationsOutstandingCount(): Int = outstandingOperations
    def totalOperationsSubmittedCount(): Long = submittedOperations
    def totalOperationsSucceededCount(): Long = succeededOperations
    def totalOperationsErroredCount(): Long = erroredOperations
    def totalOperationsCanceledCount(): Long = canceledOperations
    def acceptOperationsOutstandingCount(): Int = outstandingAccepts
    def totalAcceptOperationsSubmittedCount(): Long = submittedAccepts
    def totalAcceptOperationsSucceededCount(): Long = succeededAccepts
    def totalAcceptOperationsErroredCount(): Long = erroredAccepts
    def totalAcceptOperationsCanceledCount(): Long = canceledAccepts
    def connectOperationsOutstandingCount(): Int = outstandingConnects
    def totalConnectOperationsSubmittedCount(): Long = submittedConnects
    def totalConnectOperationsSucceededCount(): Long = succeededConnects
    def totalConnectOperationsErroredCount(): Long = erroredConnects
    def totalConnectOperationsCanceledCount(): Long = canceledConnects
    def readOperationsOutstandingCount(): Int = outstandingReads
    def totalReadOperationsSubmittedCount(): Long = submittedReads
    def totalReadOperationsSucceededCount(): Long = succeededReads
    def totalReadOperationsErroredCount(): Long = erroredReads
    def totalReadOperationsCanceledCount(): Long = canceledReads
    def writeOperationsOutstandingCount(): Int = outstandingWrites
    def totalWriteOperationsSubmittedCount(): Long = submittedWrites
    def totalWriteOperationsSucceededCount(): Long = succeededWrites
    def totalWriteOperationsErroredCount(): Long = erroredWrites
    def totalWriteOperationsCanceledCount(): Long = canceledWrites
  }

}

object SelectorSystem {

  def apply(provider: SelectorProvider): SelectorSystem =
    new SelectorSystem(provider)

  def apply(): SelectorSystem = apply(SelectorProvider.provider())

  private final class Callbacks {

    private var head: Node = null
    private var last: Node = null

    def append(interest: Int, callback: Either[Throwable, Int] => Unit): Node = {
      val node = new Node(interest, callback)
      if (last ne null) {
        last.next = node
        node.prev = last
      } else {
        head = node
      }
      last = node
      node
    }

    def iterator(): Iterator[Node] = new Iterator[Node] {
      private var _next = head

      def hasNext() = _next ne null

      def next() = {
        val next = _next
        _next = next.next
        next
      }
    }

    final class Node(
        var interest: Int,
        var callback: Either[Throwable, Int] => Unit
    ) {
      var prev: Node = null
      var next: Node = null

      def remove(): Unit = {
        if (prev ne null) prev.next = next
        else head = next

        if (next ne null) next.prev = prev
        else last = prev
      }

      def clear(): Unit = {
        interest = -1 // set all interest bits
        callback = null // clear for gc
      }
    }
  }

}
