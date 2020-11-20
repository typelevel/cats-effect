/*
 * Copyright 2020 Typelevel
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

package cats.effect.unsafe

import java.util.concurrent.atomic.AtomicReference

private final class TreiberStack {

  private[this] val top: AtomicReference[WorkerThread] = new AtomicReference()

  def push(thread: WorkerThread): Unit = {
    var oldHead: WorkerThread = null
    while ({
      oldHead = top.get()
      thread.next = oldHead
      !top.compareAndSet(oldHead, thread)
    }) ()
  }

  def pop(): WorkerThread = {
    var oldHead: WorkerThread = null
    var newHead: WorkerThread = null
    while ({
      oldHead = top.get()
      if (oldHead == null) {
        return null
      }
      newHead = oldHead.next
      !top.compareAndSet(oldHead, newHead)
    }) ()
    oldHead
  }
}
