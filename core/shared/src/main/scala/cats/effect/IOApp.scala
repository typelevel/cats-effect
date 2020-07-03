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

package cats.effect

import scala.concurrent.ExecutionContext

import java.util.concurrent.{CountDownLatch, Executors}
import java.util.concurrent.atomic.AtomicInteger

trait IOApp {

  def run(args: List[String]): IO[Int]

  final def main(args: Array[String]): Unit = {
    val runtime = Runtime.getRuntime()

    val threadCount = new AtomicInteger(0)
    val executor = Executors.newFixedThreadPool(runtime.availableProcessors(), { (r: Runnable) =>
      val t = new Thread(r)
      t.setName(s"io-compute-${threadCount.getAndIncrement()}")
      t.setDaemon(true)
      t
    })
    val context = ExecutionContext.fromExecutor(executor)

    val scheduler = Executors newSingleThreadScheduledExecutor { r =>
      val t = new Thread(r)
      t.setName("io-scheduler")
      t.setDaemon(true)
      t.setPriority(Thread.MAX_PRIORITY)
      t
    }
    val timer = UnsafeTimer.fromScheduledExecutor(scheduler)

    val latch = new CountDownLatch(1)
    @volatile var results: Either[Throwable, Int] = null

    val ioa = run(args.toList)

    val fiber = ioa.unsafeRunFiber(context, timer) { e =>
      results = e
      latch.countDown()
    }

    runtime.addShutdownHook({
      val t = new Thread({ () =>
        if (latch.getCount() > 0) {
          val cancelLatch = new CountDownLatch(1)
          fiber.cancel.unsafeRunAsync(context, timer) { _ => cancelLatch.countDown() }
          cancelLatch.await()
        }
      })

      t.setName("io-cancel-hook")
      t
    })

    // TODO in theory it's possible to just fold the main thread into the pool; should we?
    latch.await()
    results.fold(
      throw _,
      System.exit(_))
  }
}
