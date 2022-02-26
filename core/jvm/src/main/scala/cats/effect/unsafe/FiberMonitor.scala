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

import cats.effect.tracing.TracingConstants

import scala.collection.mutable
import scala.concurrent.ExecutionContext

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A slightly more involved implementation of an unordered bag used for tracking asynchronously
 * suspended fiber instances on the JVM. This bag is backed by an array of synchronized
 * `java.util.WeakHashMap` instances. This decision is based on several factors:
 *   1. A `java.util.WeakHashMap` is used because we want the resumed fibers to be automatically
 *      removed from the hash map data structure by the GC, whenever their keys expire (which is
 *      right around their resumption).
 *   1. `java.util.WeakHashMap` is **not** thread safe by nature. In the official javadoc for
 *      this class it is recommended that an instance be wrapped in
 *      `java.util.Collections.synchronizedMap` before writing to the hash map from different
 *      threads. This is absolutely crucial in our use case, because fibers can be carried by
 *      any thread (including threads external to the compute thread pool, e.g. when using
 *      `IO#evalOn`).
 *   1. Because `java.util.Collections.synchronizedMap` is a simple wrapper around any map which
 *      just synchronizes the access to the map through the built in JVM `synchronized`
 *      mechanism, we need several instances of these synchronized `WeakHashMap`s just to reduce
 *      contention between threads. A particular instance is selected using a thread local
 *      source of randomness using an instance of `java.util.concurrent.ThreadLocalRandom`.
 */
private[effect] final class FiberMonitor(
    // A reference to the compute pool of the `IORuntime` in which this suspended fiber bag
    // operates. `null` if the compute pool of the `IORuntime` is not a `WorkStealingThreadPool`.
    private[this] val compute: WorkStealingThreadPool
) extends FiberMonitorShared {

  private[this] final val Bags = FiberMonitor.Bags
  private[this] final val BagReferences = FiberMonitor.BagReferences

  /**
   * Registers a suspended fiber.
   *
   * @param fiber
   *   the suspended fiber to be registered
   * @return
   *   a handle for deregistering the fiber on resumption
   */
  def monitorSuspended(fiber: IOFiber[_]): WeakBag.Handle = {
    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerThread]) {
      val worker = thread.asInstanceOf[WorkerThread]
      // Guard against tracking errors when multiple work stealing thread pools exist.
      if (worker.isOwnedBy(compute)) {
        worker.monitor(fiber)
      } else {
        monitorFallback(fiber)
      }
    } else {
      monitorFallback(fiber)
    }
  }

  /**
   * Obtains a snapshot of the fibers currently live on the [[IORuntime]] which this fiber
   * monitor instance belongs to.
   *
   * @return
   *   a textual representation of the runtime snapshot, `None` if a snapshot cannot be obtained
   */
  def liveFiberSnapshot(print: String => Unit): Unit =
    if (TracingConstants.isStackTracing)
      Option(compute).fold {
        printFibers(foreignFibers(), "ACTIVE")(print)
        print(newline)
      } { compute =>
        val (rawExternal, workersMap, rawSuspended) = compute.liveFibers()
        val rawForeign = foreignFibers()

        // We trust the sources of data in the following order, ordered from
        // most trustworthy to least trustworthy.
        // 1. Fibers from the worker threads
        // 2. Fibers from the external queue
        // 3. Fibers from the foreign synchronized fallback weak GC maps
        // 4. Fibers from the suspended thread local GC maps

        val localAndActive = workersMap.foldLeft(Set.empty[IOFiber[_]]) {
          case (acc, (_, (active, local))) =>
            (acc ++ local) ++ active.toSet
        }
        val external = rawExternal -- localAndActive
        val suspended = rawSuspended -- localAndActive -- external
        val foreign = rawForeign -- localAndActive -- external -- suspended

        val workersStatuses = workersMap map {
          case (worker, (active, local)) =>
            val status =
              if (worker.getState() == Thread.State.RUNNABLE) "RUNNING" else "BLOCKED"

            val workerString = s"$worker (#${worker.index}): ${local.size} enqueued"

            print(doubleNewline)
            active.map(fiberString(_, status)).foreach(print(_))
            printFibers(local, "YIELDING")(print)

            workerString
        }

        printFibers(external, "YIELDING")(print)
        printFibers(suspended, "WAITING")(print)
        printFibers(foreign, "ACTIVE")(print)

        print(doubleNewline)
        print(workersStatuses.mkString(newline))

        val globalStatus =
          s"Global: enqueued ${external.size}, foreign ${foreign.size}, waiting ${suspended.size}"

        print(doubleNewline)
        print(globalStatus)
        print(newline)
      }
    else ()

  private[this] def monitorFallback(fiber: IOFiber[_]): WeakBag.Handle = {
    val bag = Bags.get()
    bag.insert(fiber)
  }

  private[this] def foreignFibers(): Set[IOFiber[_]] = {
    val foreign = mutable.Set.empty[IOFiber[_]]

    BagReferences.iterator().forEachRemaining { bag => foreign ++= bag.toSet }

    foreign.toSet
  }
}

private[effect] object FiberMonitor {
  def apply(compute: ExecutionContext): FiberMonitor = {
    if (TracingConstants.isStackTracing && compute.isInstanceOf[WorkStealingThreadPool]) {
      val wstp = compute.asInstanceOf[WorkStealingThreadPool]
      new FiberMonitor(wstp)
    } else {
      new FiberMonitor(null)
    }
  }

  private[FiberMonitor] final val Bags: ThreadLocal[WeakBag[IOFiber[_]]] =
    ThreadLocal.withInitial { () =>
      val bag = new WeakBag[IOFiber[_]]()
      BagReferences.offer(bag)
      bag
    }

  private[FiberMonitor] final val BagReferences: ConcurrentLinkedQueue[WeakBag[IOFiber[_]]] =
    new ConcurrentLinkedQueue()
}
