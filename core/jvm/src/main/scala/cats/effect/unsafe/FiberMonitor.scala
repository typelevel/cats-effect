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

import cats.effect.tracing.TracingConstants

import scala.concurrent.ExecutionContext

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
private[effect] sealed class FiberMonitor(
    // A reference to the compute pool of the `IORuntime` in which this suspended fiber bag
    // operates. `null` if the compute pool of the `IORuntime` is not a `WorkStealingThreadPool`.
    private[this] val compute: WorkStealingThreadPool[_]
) extends FiberMonitorShared {

  private[this] final val BagReferences = new WeakList[WeakBag[Runnable]]
  private[this] final val Bags = ThreadLocal.withInitial { () =>
    val bag = new WeakBag[Runnable]()
    BagReferences.prepend(bag)
    bag
  }

  private[this] val justFibers: PartialFunction[(Runnable, Trace), (IOFiber[_], Trace)] = {
    case (fiber: IOFiber[_], trace) => fiber -> trace
  }

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
    if (thread.isInstanceOf[WorkerThread[_]]) {
      val worker = thread.asInstanceOf[WorkerThread[_]]
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
   * `print` function can be used to print or accumulate a textual representation of the runtime
   * snapshot.
   *
   * @example
   *   Accumulate a live snapshot
   *   {{{
   *   val monitor: FiberMonitor = ???
   *   val buffer = new ArrayBuffer[String]
   *   monitor.liveFiberSnapshot(buffer += _)
   *   buffer.toArray
   *   }}}
   *
   * @example
   *   Print a live snapshot
   *   {{{
   *   val monitor: FiberMonitor = ???
   *   monitor.liveFiberSnapshot(System.out.print(_))
   *   }}}
   */
  def liveFiberSnapshot(print: String => Unit): Unit =
    if (TracingConstants.isStackTracing)
      Option(compute).fold {
        printFibers(foreignFibers(), "ACTIVE")(print)
        print(newline)
      } { compute =>
        val (rawExternal, workersMap, rawSuspended) = {
          val (external, workers, suspended) = compute.liveTraces()
          val externalFibers = external.collect(justFibers)
          val suspendedFibers = suspended.collect(justFibers)
          val workersMapping: Map[
            WorkerThread[_],
            (Thread.State, Option[(IOFiber[_], Trace)], Map[IOFiber[_], Trace])] =
            workers.map {
              case (thread, (state, opt, set)) =>
                val filteredOpt = opt.collect(justFibers)
                val filteredSet = set.collect(justFibers)
                (thread, (state, filteredOpt, filteredSet))
            }.toMap

          (externalFibers, workersMapping, suspendedFibers)
        }
        val rawForeign = foreignFibers()

        // We trust the sources of data in the following order, ordered from
        // most trustworthy to least trustworthy.
        // 1. Fibers from the worker threads
        // 2. Fibers from the external queue
        // 3. Fibers from the foreign synchronized fallback weak GC maps
        // 4. Fibers from the suspended thread local GC maps

        val localAndActive = workersMap.foldLeft(Map.empty[IOFiber[_], Trace]) {
          case (acc, (_, (_, active, local))) =>
            (acc ++ local) ++ active.collect(justFibers)
        }
        val external = rawExternal -- localAndActive.keys
        val suspended = rawSuspended -- localAndActive.keys -- external.keys
        val foreign = rawForeign -- localAndActive.keys -- external.keys -- suspended.keys

        val workersStatuses = workersMap map {
          case (worker, (state, active, local)) =>
            val yielding = local

            val status =
              if (state == Thread.State.RUNNABLE) "RUNNING" else "BLOCKED"

            val workerString = s"$worker (#${worker.index}): ${yielding.size} enqueued"

            print(doubleNewline)
            active
              .map { case (fiber, trace) => fiberString(fiber, trace, status) }
              .foreach(print(_))
            printFibers(yielding, "YIELDING")(print)

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
    val handle = bag.insert(fiber)
    bag.synchronizationPoint.lazySet(true)
    handle
  }

  /**
   * Returns a set of active fibers (SUSPENDED or RUNNING). Completed fibers are filtered out.
   *
   * @see
   *   [[cats.effect.IOFiber.isDone IOFiber#isDone]] for 'completed' condition
   *
   * @return
   *   a set of active fibers
   */
  private[this] def foreignFibers(): Map[IOFiber[_], Trace] = {
    val foreign = Map.newBuilder[IOFiber[_], Trace]

    BagReferences.foreach { bag =>
      val _ = bag.synchronizationPoint.get()
      bag.forEach {
        case fiber: IOFiber[_] if !fiber.isDone =>
          foreign += (fiber.asInstanceOf[IOFiber[Any]] -> fiber.captureTrace())
        case _ => ()
      }
    }

    foreign.result()
  }
}

private[effect] final class NoOpFiberMonitor extends FiberMonitor(null) {
  private final val noop: WeakBag.Handle = () => ()
  override def monitorSuspended(fiber: IOFiber[_]): WeakBag.Handle = noop
  override def liveFiberSnapshot(print: String => Unit): Unit = {}
}

private[effect] object FiberMonitor {
  def apply(compute: ExecutionContext): FiberMonitor = {
    if (TracingConstants.isStackTracing && compute.isInstanceOf[WorkStealingThreadPool[_]]) {
      val wstp = compute.asInstanceOf[WorkStealingThreadPool[_]]
      new FiberMonitor(wstp)
    } else {
      new FiberMonitor(null)
    }
  }
}
