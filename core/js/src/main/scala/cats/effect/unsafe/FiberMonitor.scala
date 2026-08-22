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

import scala.concurrent.ExecutionContext
import scala.scalajs.{js, LinkingInfo}

private[effect] sealed abstract class FiberMonitor extends FiberMonitorShared {

  /**
   * Registers a suspended fiber.
   *
   * @param fiber
   *   the suspended fiber to be registered
   * @return
   *   a handle for deregistering the fiber on resumption
   */
  def monitorSuspended(fiber: IOFiber[?]): WeakBag.Handle

  /**
   * Obtains a snapshot of the fibers currently live on the [[IORuntime]] which this fiber
   * monitor instance belongs to.
   *
   * `print` function can be used to print or accumulate a textual representation of the runtime
   * snapshot.
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
  def printLiveFiberSnapshot(print: String => Unit): Unit

  /**
   * Obtains a snapshot of the fibers currently live on the [[IORuntime]] which this fiber
   * monitor instance belongs to.
   *
   * The returned [[FiberSnapshot]] contains hard references to the underlying fibers and their
   * current state, and execution traces. It can be used for debugging, diagnostics, or
   * visualizations.
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
   *       snapshot <- IO.delay(runtime.liveFiberSnapshot())
   *       _ <- snapshot.external.traverse_(fiber => IO.println(fiber.pretty))
   *     } yield ()
   *   }}}
   *
   * @see
   *   [[printLiveFiberSnapshot]] for printing a human-readable snapshot
   */
  def liveFiberSnapshot(): FiberSnapshot
}

private final class FiberMonitorImpl(
    // A reference to the compute pool of the `IORuntime` in which this suspended fiber bag
    // operates. `null` if the compute pool of the `IORuntime` is not a `BatchingMacrotaskExecutor`.
    private[this] val compute: BatchingMacrotaskExecutor
) extends FiberMonitor {
  private[this] val bag = new WeakBag[IOFiber[?]]()

  override def monitorSuspended(fiber: IOFiber[?]): WeakBag.Handle =
    bag.insert(fiber)

  private[this] def foreignTraces(): Map[IOFiber[?], Trace] = {
    val foreign = Map.newBuilder[IOFiber[?], Trace]
    bag.forEach(fiber =>
      if (!fiber.isDone) foreign += (fiber.asInstanceOf[IOFiber[Any]] -> fiber.captureTrace()))
    foreign.result()
  }

  def printLiveFiberSnapshot(print: String => Unit): Unit = {
    val snapshot = liveFiberSnapshot()

    val (enqueued, waiting) = snapshot.external.foldLeft((0, 0)) {
      case ((enqueued, waiting), fiber) =>
        fiber.state match {
          case FiberInfo.State.Yielding => (enqueued + 1, waiting)
          case FiberInfo.State.Waiting => (enqueued, waiting + 1)
          case _ => (enqueued, waiting)
        }
    }

    printFibers(snapshot.external)(print)

    val globalStatus =
      s"Global: enqueued $enqueued, waiting $waiting"

    print(doubleNewline)
    print(globalStatus)
    print(newline)
  }

  def liveFiberSnapshot(): FiberSnapshot = {
    Option(compute).fold(FiberSnapshot.empty) { compute =>
      val queued = compute.liveTraces()
      val rawForeign = foreignTraces()

      // We trust the sources of data in the following order, ordered from
      // most trustworthy to least trustworthy.
      // 1. Fibers from the fiber executor
      // 2. Fibers from the foreign fallback weak GC map

      val allForeign = rawForeign -- queued.keys
      val (suspended, foreign) = allForeign.partition { case (f, _) => f.get() }

      val fibers =
        toFiberInfo(queued, FiberInfo.State.Yielding) ++
          toFiberInfo(foreign, FiberInfo.State.Yielding) ++
          toFiberInfo(suspended, FiberInfo.State.Waiting)

      FiberSnapshot(fibers)
    }
  }
}

/**
 * A no-op implementation of an unordered bag used for tracking asynchronously suspended fiber
 * instances on Scala Native. This is used as a fallback.
 */
private final class NoOpFiberMonitor extends FiberMonitor {
  override def monitorSuspended(fiber: IOFiber[?]): WeakBag.Handle = () => ()
  def printLiveFiberSnapshot(print: String => Unit): Unit = ()
  def liveFiberSnapshot(): FiberSnapshot = FiberSnapshot.empty
}

private[effect] object FiberMonitor {
  def apply(compute: ExecutionContext): FiberMonitor = {
    if (LinkingInfo.developmentMode && weakRefsAvailable) {
      if (compute.isInstanceOf[BatchingMacrotaskExecutor]) {
        val bmec = compute.asInstanceOf[BatchingMacrotaskExecutor]
        new FiberMonitorImpl(bmec)
      } else {
        new FiberMonitorImpl(null)
      }
    } else {
      new NoOpFiberMonitor()
    }
  }

  private[this] final val Undefined = "undefined"

  /**
   * Feature-tests for all the required, well, features :)
   */
  private[unsafe] def weakRefsAvailable: Boolean =
    js.typeOf(js.Dynamic.global.WeakRef) != Undefined &&
      js.typeOf(js.Dynamic.global.FinalizationRegistry) != Undefined
}
