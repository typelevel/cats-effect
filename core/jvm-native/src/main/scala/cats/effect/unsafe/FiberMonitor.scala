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
    private[this] val compute: WorkStealingThreadPool[?]
) extends FiberMonitorShared {

  private[this] final val BagReferences = new WeakList[WeakBag[Runnable]]
  private[this] final val Bags = ThreadLocal.withInitial { () =>
    val bag = new WeakBag[Runnable]()
    BagReferences.prepend(bag)
    bag
  }

  private[this] val justFibers: PartialFunction[(Runnable, Trace), (IOFiber[?], Trace)] = {
    case (fiber: IOFiber[?], trace) => fiber -> trace
  }

  /**
   * Registers a suspended fiber.
   *
   * @param fiber
   *   the suspended fiber to be registered
   * @return
   *   a handle for deregistering the fiber on resumption
   */
  def monitorSuspended(fiber: IOFiber[?]): WeakBag.Handle = {
    val thread = Thread.currentThread()
    if (thread.isInstanceOf[WorkerThread[?]]) {
      val worker = thread.asInstanceOf[WorkerThread[?]]
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
   *   monitor.printLiveFiberSnapshot(buffer += _)
   *   buffer.toArray
   *   }}}
   *
   * @example
   *   Print a live snapshot
   *   {{{
   *   val monitor: FiberMonitor = ???
   *   monitor.printLiveFiberSnapshot(System.out.print(_))
   *   }}}
   *
   * @see
   *   [[liveFiberSnapshot]]
   */
  def printLiveFiberSnapshot(print: String => Unit): Unit = {
    val snapshot = liveFiberSnapshot()

    if (snapshot.workers.isEmpty) {
      printFibers(snapshot.external)(print)
    } else {
      val (enqueued, foreign, waiting) = snapshot.external.foldLeft((0, 0, 0)) {
        case ((enqueued, foreign, waiting), fiber) =>
          fiber.state match {
            case FiberInfo.State.Yielding => (enqueued + 1, foreign, waiting)
            case FiberInfo.State.Active => (enqueued, foreign + 1, waiting)
            case FiberInfo.State.Waiting => (enqueued, foreign, waiting + 1)
            case _ => (enqueued, foreign, waiting)
          }
      }

      val workersStatuses = snapshot.workers.map {
        case (worker, fibers) =>
          val yielding = fibers.collect {
            case fiber: FiberInfo if fiber.state == FiberInfo.State.Yielding => fiber
          }.size

          printFibers(fibers)(print)

          s"${worker.thread} (#${worker.index}): $yielding enqueued"
      }

      printFibers(snapshot.external)(print)

      print(doubleNewline)
      print(workersStatuses.mkString(newline))

      val globalStatus =
        s"Global: enqueued $enqueued, foreign $foreign, waiting $waiting"

      print(doubleNewline)
      print(globalStatus)
      print(newline)
    }
  }

  /**
   * Obtains a snapshot of the fibers currently live on the [[IORuntime]] which this fiber
   * monitor instance belongs to.
   *
   * The returned [[FiberSnapshot]] contains hard references to the underlying fibers and their
   * current state, worker assignments, and execution traces. It can be used for debugging,
   * diagnostics, or visualizations.
   *
   * @note
   *   the snapshot introduces a risk of memory leaks because it retains hard references to the
   *   underlying Fiber instances. As a result, these fibers cannot be garbage collected while
   *   the snapshot (or anything that retains it) is still in scope.
   *
   * @example
   *   Print all live fibers grouped by worker:
   *   {{{
   *   val printFiberDump: IO[Unit] =
   *     for {
   *       // Take a snapshot of all live fibers in the current IORuntime
   *       snapshot <- IO.delay(runtime.liveFiberSnapshot())
   *
   *       // Print fibers assigned to specific worker threads
   *       _ <- snapshot.workers.toList.traverse_ { case (worker, fibers) =>
   *         IO.println(s"Worker ${worker.thread} #${worker.index}") >>
   *           fibers.traverse_(fiber => IO.println(fiber.pretty))
   *       }
   *
   *       // Print global fibers (not bound to specific workers, e.g., blocked or external)
   *       _ <- snapshot.external.traverse_(fiber => IO.println(fiber.pretty))
   *     } yield ()
   *   }}}
   *
   * @see
   *   [[printLiveFiberSnapshot]] for printing a human-readable snapshot
   */
  def liveFiberSnapshot(): FiberSnapshot =
    if (TracingConstants.isStackTracing) collectSnapshot() else FiberSnapshot.empty

  private def collectSnapshot(): FiberSnapshot =
    Option(compute).fold {
      val fibers = foreignFibers().map {
        case (fiber, trace) =>
          FiberInfo(fiber, FiberInfo.State.Active, trace)
      }
      FiberSnapshot(fibers.toList, Map.empty)
    } { compute =>
      val (rawExternal, workersMap, rawSuspended) = {
        val (external, workers, suspended) = compute.liveTraces()
        val externalFibers = external.collect(justFibers)
        val suspendedFibers = suspended.collect(justFibers)
        val workersMapping: Map[
          WorkerThread[?],
          (Thread.State, Option[(IOFiber[?], Trace)], Map[IOFiber[?], Trace])] =
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

      val localAndActive = workersMap.foldLeft(Map.empty[IOFiber[?], Trace]) {
        case (acc, (_, (_, active, local))) =>
          (acc ++ local) ++ active.collect(justFibers)
      }
      val external = rawExternal -- localAndActive.keys
      val suspended = rawSuspended -- localAndActive.keys -- external.keys
      val foreign = rawForeign -- localAndActive.keys -- external.keys -- suspended.keys

      val workersStatuses = workersMap map {
        case (worker, (threadState, active, local)) =>
          val yielding = local

          val state =
            if (threadState == Thread.State.RUNNABLE) FiberInfo.State.Running
            else FiberInfo.State.Blocked

          val fibers =
            toFiberInfo(active.toMap, state) ++
              toFiberInfo(yielding, FiberInfo.State.Yielding)

          (WorkerInfo(worker, worker.index), fibers)
      }

      val global =
        toFiberInfo(external, FiberInfo.State.Yielding) ++
          toFiberInfo(suspended, FiberInfo.State.Waiting) ++
          toFiberInfo(foreign, FiberInfo.State.Active)

      FiberSnapshot(global, workersStatuses)
    }

  private[this] def monitorFallback(fiber: IOFiber[?]): WeakBag.Handle = {
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
  private[this] def foreignFibers(): Map[IOFiber[?], Trace] = {
    val foreign = Map.newBuilder[IOFiber[?], Trace]

    BagReferences.foreach { bag =>
      val _ = bag.synchronizationPoint.get()
      bag.forEach {
        case fiber: IOFiber[?] if !fiber.isDone =>
          foreign += (fiber.asInstanceOf[IOFiber[Any]] -> fiber.captureTrace())
        case _ => ()
      }
    }

    foreign.result()
  }
}

private[effect] final class NoOpFiberMonitor extends FiberMonitor(null) {
  private final val noop: WeakBag.Handle = () => ()
  override def monitorSuspended(fiber: IOFiber[?]): WeakBag.Handle = noop
  override def printLiveFiberSnapshot(print: String => Unit): Unit = {}
}

private[effect] object FiberMonitor {
  def apply(compute: ExecutionContext): FiberMonitor = if (Platform.isJvm) {
    if (TracingConstants.isStackTracing && compute.isInstanceOf[WorkStealingThreadPool[?]]) {
      val wstp = compute.asInstanceOf[WorkStealingThreadPool[?]]
      new FiberMonitor(wstp)
    } else {
      new FiberMonitor(null)
    }
  } else new NoOpFiberMonitor
}
