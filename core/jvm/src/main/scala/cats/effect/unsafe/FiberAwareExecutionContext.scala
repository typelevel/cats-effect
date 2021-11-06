/*
 * Copyright 2020-2021 Typelevel
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

import scala.collection.mutable
import scala.concurrent.ExecutionContext

import java.lang.ref.WeakReference
import java.util.{Map, WeakHashMap}

private final class FiberAwareExecutionContext(ec: ExecutionContext, monitor: FiberMonitor)
    extends ExecutionContext {

  private[this] val localBag = new ThreadLocal[Map[AnyRef, WeakReference[IOFiber[_]]]] {
    override def initialValue(): Map[AnyRef, WeakReference[IOFiber[_]]] = {
      val bag = new WeakHashMap[AnyRef, WeakReference[IOFiber[_]]]
      monitor.registerExtraBag(bag)
      bag
    }
  }

  def liveFibers(): Set[IOFiber[_]] = fiberBag.toSet

  private[this] val fiberBag = mutable.Set.empty[IOFiber[_]]

  def execute(runnable: Runnable): Unit = runnable match {
    case f: IOFiber[_] =>
      val r: Runnable = () => f.run()
      localBag.get().put(r, new WeakReference(f))
      ec.execute(r)

    case r => r.run()
  }

  def reportFailure(cause: Throwable): Unit = ec.reportFailure(cause)

}
