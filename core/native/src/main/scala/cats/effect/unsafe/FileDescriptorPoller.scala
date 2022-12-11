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

trait FileDescriptorPoller {

  /**
   * Registers a callback to be notified of read- and write-ready events on a file descriptor.
   * Produces a runnable which unregisters the file descriptor.
   *
   *   1. It is the responsibility of the caller to set the file descriptor to non-blocking
   *      mode.
   *   1. It is the responsibility of the caller to unregister the file descriptor when they are
   *      done.
   *   1. A file descriptor should be registered at most once. To modify a registration, you
   *      must unregister and re-register the file descriptor.
   *   1. The callback may be invoked "spuriously" claiming that a file descriptor is read- or
   *      write-ready when in fact it is not. You should be prepared to handle this.
   *   1. The callback will be invoked at least once when the file descriptor transitions from
   *      blocked to read- or write-ready. You may additionally receive zero or more reminders
   *      of its readiness. However, you should not rely on any further callbacks until after
   *      the file descriptor has become blocked again.
   */
  def registerFileDescriptor(
      fileDescriptor: Int,
      readReadyEvents: Boolean,
      writeReadyEvents: Boolean)(
      cb: FileDescriptorPoller.Callback
  ): Runnable

}

object FileDescriptorPoller {
  trait Callback {
    def apply(readReady: Boolean, writeReady: Boolean): Unit
  }
}
