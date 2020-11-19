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

/*
 * This code is an adaptation of the `Inject` queue from the `tokio` runtime.
 * The original source code in Rust is licensed under the MIT license and available
 * at: https://docs.rs/crate/tokio/0.2.22/source/src/runtime/queue.rs.
 *
 * For the reasoning behind the design decisions of this code, please consult:
 * https://tokio.rs/blog/2019-10-scheduler#the-next-generation-tokio-scheduler.
 */

package cats.effect
package unsafe

/**
 * Multi producer, multi consumer linked queue of `IOFiber[_]` objects.
 * This class cannot function without the `IOFiber#next` field, which is used
 * to achieve allocation free linked queue semantics.
 *
 * New fibers scheduled by external threads end up at the back of this queue.
 * When a local worker queue overflows (cannot accept new fibers), half of it
 * gets spilled over to this queue.
 */
private final class ExternalQueue {
  import WorkStealingQueueConstants._

  // Lock that ensures exclusive access to the internal state of the linked queue.
  private[this] val lock: AnyRef = new Object()

  // Pointer to the least recently added fiber in the queue.
  // The next to be dequeued when calling `dequeue`.
  private[this] var head: IOFiber[_] = null

  // Pointer to the most recently added fiber in the queue.
  private[this] var tail: IOFiber[_] = null

  // Tracks whether the queue has been shutdown.
  private[this] var _shutdown: Boolean = false

  // Number of enqueued fibers. Used as a fast-path to avoid unnecessary locking
  // on the fast path. Can be accessed without holding the lock.
  private[this] var len: Int = 0

  /**
   * Enqueues a fiber for later execution at the back of the queue.
   */
  def enqueue(fiber: IOFiber[_]): Unit = {
    lock.synchronized {
      if (_shutdown) {
        // Do not accept new fibers if the queue has been shut down.
        return
      }

      // Safe to mutate the internal state because we are holding the lock.
      if (tail != null) {
        // The queue is not empty, put the new fiber at the back of the queue.
        tail.next = fiber
      } else {
        // The queue is empty, the new fiber becomes the head of the queue.
        head = fiber
      }

      // Set the tail to point to the new fiber.
      tail = fiber

      len += 1
    }
  }

  /**
   * Enqueues a linked list of fibers for later execution at the back of the queue.
   */
  def enqueueBatch(hd: IOFiber[_], tl: IOFiber[_]): Unit = {
    lock.synchronized {
      // Safe to mutate the internal state because we are holding the lock.
      if (tail != null) {
        // The queue is not empty, put the head of the fiber batch at the back of the queue.
        tail.next = hd
      } else {
        // The queue is empty, the head of the fiber batch becomes the head of the queue.
        head = hd
      }

      tail = tl

      len += BatchLength
    }
  }

  /**
   * Dequeues the least recently added fiber from the front of the queue for execution.
   * Returns `null` if the queue is empty.
   */
  def dequeue(): IOFiber[_] = {
    // Fast path, no locking.
    if (isEmpty()) {
      return null
    }

    lock.synchronized {
      // Obtain the head of the queue.
      val fiber = head

      // The queue could have been emptied by the time we acquired the lock.
      if (fiber == null) {
        // Nothing to do, return.
        return null
      }

      // Make the head of the queue point to the next fiber.
      head = fiber.next

      // If the new head is `null`, the queue is empty. Make sure the tail is consistent.
      if (head == null) {
        tail = null
      }

      // Unlink the fiber from the linked queue before returning.
      fiber.next = null

      len -= 1

      fiber
    }
  }

  /**
   * Returns true if there are no enqueued fibers.
   */
  def isEmpty(): Boolean = {
    val l = len
    Unsafe.acquireFence()
    l == 0
  }

  /**
   * Shutdown, drain and unlink the queue. No more fibers can be enqueued after
   * this call. Repeated calls have no effect.
   */
  def shutdown(): Unit = {
    lock.synchronized {
      if (_shutdown) {
        // The queue has already been shutdown. Return.
        return
      }

      // Unlink and drain the queue.
      var fiber: IOFiber[_] = head
      var next: IOFiber[_] = null
      while (fiber != null) {
        next = fiber.next
        fiber.next = null
        fiber = next
      }

      // Set the shutdown flag.
      _shutdown = true
    }
  }
}
