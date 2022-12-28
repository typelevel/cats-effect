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

package cats.effect
package unsafe

import cats.~>

import java.nio.channels.SelectableChannel
import java.nio.channels.spi.{AbstractSelector, SelectorProvider}

import SelectorSystem._

final class SelectorSystem private (provider: SelectorProvider) extends PollingSystem {

  def makePoller(delayWithData: (PollData => *) ~> IO): Poller =
    new Poller(delayWithData, provider)

  def makePollData(): PollData = new PollData(provider.openSelector())

  def closePollData(data: PollData): Unit =
    data.selector.close()

  def poll(data: PollData, nanos: Long, reportFailure: Throwable => Unit): Boolean = {
    val millis = if (nanos >= 0) nanos / 1000000 else -1
    val selector = data.selector

    if (millis == 0) selector.selectNow()
    else if (millis > 0) selector.select(millis)
    else selector.select()

    if (selector.isOpen()) { // closing selector interrupts select
      val ready = selector.selectedKeys().iterator()
      while (ready.hasNext()) {
        val key = ready.next()
        ready.remove()

        val readyOps = key.readyOps()
        val value = Right(readyOps)

        var head: CallbackNode = null
        var prev: CallbackNode = null
        var node = key.attachment().asInstanceOf[CallbackNode]
        while (node ne null) {
          val next = node.next

          if ((node.interest & readyOps) != 0) { // execute callback and drop this node
            val cb = node.callback
            if (cb != null) cb(value)
            if (prev ne null) prev.next = next
          } else { // keep this node
            prev = node
            if (head eq null)
              head = node
          }

          node = next
        }

        // reset interest in triggered ops
        key.interestOps(key.interestOps() & ~readyOps)
        key.attach(head)
      }

      !selector.keys().isEmpty()
    } else false
  }

  def interrupt(targetThread: Thread, targetData: PollData): Unit = {
    targetData.selector.wakeup()
    ()
  }

  final class Poller private[SelectorSystem] (
      delayWithData: (PollData => *) ~> IO,
      val provider: SelectorProvider
  ) extends SelectorPoller {

    def select(ch: SelectableChannel, ops: Int): IO[Int] = IO.async { cb =>
      delayWithData { data =>
        val selector = data.selector
        val key = ch.keyFor(selector)

        val node = if (key eq null) { // not yet registered on this selector
          val node = new CallbackNode(ops, cb, null)
          ch.register(selector, ops, node)
          node
        } else { // existing key
          // mixin the new interest
          key.interestOps(key.interestOps() | ops)
          val node = new CallbackNode(ops, cb, key.attachment().asInstanceOf[CallbackNode])
          key.attach(node)
          node
        }

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

  final class PollData private[SelectorSystem] (
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
