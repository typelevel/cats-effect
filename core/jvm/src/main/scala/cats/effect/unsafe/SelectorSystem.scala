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

import SelectorSystem._

final class SelectorSystem private (provider: SelectorProvider) extends PollingSystem {

  type Api = Selector

  def close(): Unit = ()

  def makeApi(access: (Poller => Unit) => Unit): Selector =
    new SelectorImpl(access, provider)

  def makePoller(): Poller = new Poller(provider.openSelector())

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

        var head: CallbackNode = null
        var prev: CallbackNode = null
        var node = key.attachment().asInstanceOf[CallbackNode]
        while (node ne null) {
          val next = node.next

          if ((node.interest & readyOps) != 0) { // execute callback and drop this node
            val cb = node.callback
            if (cb != null) {
              cb(value)
              polled = true
            }
            if (prev ne null) prev.next = next
          } else { // keep this node
            prev = node
            if (head eq null)
              head = node
          }

          node = next
        }

        key.attach(head) // if key was canceled this will null attachment
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
      access: (Poller => Unit) => Unit,
      val provider: SelectorProvider
  ) extends Selector {

    def select(ch: SelectableChannel, ops: Int): IO[Int] = IO.async { selectCb =>
      IO.async_[CallbackNode] { cb =>
        access { data =>
          try {
            val selector = data.selector
            val key = ch.keyFor(selector)

            val node = if (key eq null) { // not yet registered on this selector
              val node = new CallbackNode(ops, selectCb, null)
              ch.register(selector, ops, node)
              node
            } else { // existing key
              // mixin the new interest
              key.interestOps(key.interestOps() | ops)
              val node =
                new CallbackNode(ops, selectCb, key.attachment().asInstanceOf[CallbackNode])
              key.attach(node)
              node
            }

            cb(Right(node))
          } catch { case ex if NonFatal(ex) => cb(Left(ex)) }
        }
      }.map { node =>
        Some {
          IO {
            // set all interest bits
            node.interest = -1
            // clear for gc
            node.callback = null
          }
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

  private final class CallbackNode(
      var interest: Int,
      var callback: Either[Throwable, Int] => Unit,
      var next: CallbackNode
  )
}
