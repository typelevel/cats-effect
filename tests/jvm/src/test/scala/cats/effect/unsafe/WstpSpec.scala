/*
 * Copyright 2020-2023 Typelevel
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

import scala.concurrent.duration._

import java.util.concurrent.atomic.AtomicLong

/**
 * Demonstrates a problem with `cedeBypass` being lost.
 */
final class WstpSpec {

  val ctr = new AtomicLong

  val wstp = new WorkStealingThreadPool(
    threadCount = 2,
    threadPrefix = "testWorker",
    blockerThreadPrefix = "testBlocker",
    runtimeBlockingExpiration = 3.seconds,
    reportFailure0 = _.printStackTrace()
  )

  val runtime = IORuntime
    .builder()
    .setCompute(wstp, () => wstp.shutdown())
    .setConfig(IORuntimeConfig(cancelationCheckThreshold = 1, autoYieldThreshold = 2))
    .build()

  def go(): Unit = {
    val tsk1 = IO { ctr.incrementAndGet() }.foreverM
    val fib1 = tsk1.unsafeRunFiber((), _ => (), { (_: Any) => () })(runtime)
    for (_ <- 1 to 10) {
      val tsk2 = IO.blocking { Thread.sleep(5L) }
      tsk2.unsafeRunFiber((), _ => (), _ => ())(runtime)
    }
    fib1.join.unsafeRunFiber((), _ => (), _ => ())(runtime)
    var c = 0
    while (c < 5000000) {
      Thread.sleep(1000L)
      println(ctr.get())
      c += 1
    }
    ()
  }
}

object WstpSpec {
  def main(args: Array[String]): Unit = {
    println("Hello!")
    val spec = new WstpSpec()
    spec.go()
    println("Done.")
  }
}
