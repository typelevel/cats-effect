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

package cats.effect
package unsafe

import scala.util.control.NonFatal

import java.nio.channels.SelectableChannel
import java.nio.channels.spi.{AbstractSelector, SelectorProvider}
import java.util.Iterator

import SelectorSystem._

final class SelectorSystem private (selectorProvider: SelectorProvider) extends PollingSystem {

  type Api = Selector

  def close(): Unit = ()

  def makeApi(provider: PollerProvider[Poller]): Selector =
    new SelectorImpl(provider, selectorProvider)

  def makePoller(): Poller = new Poller(selectorProvider.openSelector())

  def closePoller(poller: Poller): Unit =
    poller.selector.close()

  def poll(poller: Poller, nanos: Long, reportFailure: Throwable => Unit): Boolean = {
    val millis = if (nanos >= 0) nanos / 1000000 else -1
    val selector = poller.selector

    if (millis == 0) selector.selectNow()
    else if (millis > 0) selector.select(millis)
    else selector.select()

    if (selector.isOpen()) { // closing selector interrupts select
      var polled = false

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
          case ex if NonFatal(ex) =>
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
              polled = true
            }
          }
        }

        ()
      }

      polled
    } else false
  }

  def needsPoll(poller: Poller): Boolean =
    !poller.selector.keys().isEmpty()

  def interrupt(targetThread: Thread, targetPoller: Poller): Unit = {
    targetPoller.selector.wakeup()
    ()
  }

  final class SelectorImpl private[SelectorSystem] (
      pollerProvider: PollerProvider[Poller],
      val provider: SelectorProvider
  ) extends Selector {

    def select(ch: SelectableChannel, ops: Int): IO[Int] = IO.async { selectCb =>
      IO.async_[Option[IO[Unit]]] { cb =>
        pollerProvider.accessPoller { poller =>
          try {
            val selector = poller.selector
            val key = ch.keyFor(selector)

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
              if (pollerProvider.ownPoller(poller))
                node.remove()
              else
                node.clear()
            }

            cb(Right(Some(cancel)))
          } catch { case ex if NonFatal(ex) => cb(Left(ex)) }
        }
      }
    }

  }

  final class Poller private[SelectorSystem] (
      private[SelectorSystem] val selector: AbstractSelector
  )

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
