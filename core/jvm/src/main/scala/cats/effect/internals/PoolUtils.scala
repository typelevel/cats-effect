/*
 * Copyright (c) 2017-2019 The Typelevel Cats-effect Project Developers
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
package internals

import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

import java.util.concurrent.{Executors, ThreadFactory}
import java.util.concurrent.atomic.AtomicInteger

private[internals] object PoolUtils {
  // we can initialize this eagerly because the enclosing object is lazy
  val ioAppGlobal: ExecutionContext = {
    // lower-bound of 2 to prevent pathological deadlocks on virtual machines
    val bound = math.max(2, Runtime.getRuntime().availableProcessors())

    val executor = Executors.newFixedThreadPool(
      bound,
      new ThreadFactory {
        val ctr = new AtomicInteger(0)
        def newThread(r: Runnable): Thread = {
          val back = new Thread(r, s"ioapp-compute-${ctr.getAndIncrement()}")
          back.setDaemon(true)
          back
        }
      }
    )

    exitOnFatal(ExecutionContext.fromExecutor(executor))
  }

  def exitOnFatal(ec: ExecutionContext): ExecutionContext = new ExecutionContext {
    def execute(r: Runnable): Unit =
      ec.execute(new Runnable {
        def run(): Unit =
          try {
            r.run()
          } catch {
            case NonFatal(t) =>
              reportFailure(t)

            case t: Throwable =>
              // under most circumstances, this will work even with fatal errors
              t.printStackTrace()
              System.exit(1)
          }
      })

    def reportFailure(t: Throwable): Unit =
      ec.reportFailure(t)
  }
}
