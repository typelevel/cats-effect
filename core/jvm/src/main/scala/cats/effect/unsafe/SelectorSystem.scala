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

import scala.concurrent.ExecutionContext

import java.nio.channels.SelectableChannel
import java.nio.channels.spi.SelectorProvider
import java.nio.channels.spi.AbstractSelector

import SelectorSystem._

final class SelectorSystem private (provider: SelectorProvider) extends PollingSystem {

  def makePoller(ec: ExecutionContext, data: () => PollData): Poller =
    new Poller(ec, data, provider)

  def makePollData(): PollData = new PollData(provider.openSelector())

  def closePollData(data: PollData): Unit =
    data.selector.close()

  def poll(data: PollData, nanos: Long, reportFailure: Throwable => Unit): Boolean = {
    val millis = if (nanos >= 0) nanos / 1000000 else -1
    val selector = data.selector

    if (millis == 0) selector.selectNow()
    else if (millis > 0) selector.select(millis)
    else selector.select()

    val ready = selector.selectedKeys().iterator()
    while (ready.hasNext()) {
      val key = ready.next()
      ready.remove()

      val attachment = key.attachment().asInstanceOf[Attachment]
      val interest = attachment.interest
      val readyOps = key.readyOps()

      if ((interest & readyOps) != 0) {
        val value = Right(readyOps)

        var head: CallbackNode = null
        var prev: CallbackNode = null
        var node = attachment.callbacks
        while (node ne null) {
          if ((node.interest & readyOps) != 0) { // execute callback and drop this node
            val cb = node.callback
            if (cb != null) cb(value)
            if (prev ne null) prev.next = node.next
          } else { // keep this node
            prev = node
            if (head eq null)
              head = node
          }

          node = node.next
        }

        // reset interest in triggered ops
        val newInterest = interest & ~readyOps
        attachment.interest = newInterest
        attachment.callbacks = head
        key.interestOps(newInterest)
      }
    }

    !selector.keys().isEmpty()
  }

  def interrupt(targetThread: Thread, targetData: PollData): Unit = {
    targetData.selector.wakeup()
    ()
  }

  final class Poller private[SelectorSystem] (
      ec: ExecutionContext,
      data: () => PollData,
      val provider: SelectorProvider
  ) extends SelectorPoller {

    def register(ch: SelectableChannel, ops: Int): IO[Int] = IO.async { cb =>
      IO {
        val selector = data().selector
        val key = ch.register(selector, ops) // this overrides existing ops interest. annoying
        val attachment = key.attachment().asInstanceOf[Attachment]

        val node = if (attachment eq null) { // newly registered on this selector
          val node = new CallbackNode(ops, cb, null)
          key.attach(new Attachment(ops, node))
          node
        } else { // existing key
          val interest = attachment.interest
          val newInterest = interest | ops
          if (interest != newInterest) { // need to restore the existing interest
            attachment.interest = newInterest
            key.interestOps(newInterest)
          }
          val node = new CallbackNode(ops, cb, attachment.callbacks)
          attachment.callbacks = node
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
      }.evalOn(ec)
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

  private final class Attachment(
      var interest: Int,
      var callbacks: CallbackNode
  )

  private final class CallbackNode(
      var interest: Int,
      var callback: Either[Throwable, Int] => Unit,
      var next: CallbackNode
  )
}
