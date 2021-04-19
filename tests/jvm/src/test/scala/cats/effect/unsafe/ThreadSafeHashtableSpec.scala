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

import cats.syntax.traverse._

import java.util.concurrent.CountDownLatch

class ThreadSafeHashtableSpec extends BaseSpec with Runners {

  def hashtableRuntime(): IORuntime = {
    lazy val rt: IORuntime = {
      val (blocking, blockDown) =
        IORuntime.createDefaultBlockingExecutionContext(threadPrefix =
          s"io-blocking-${getClass.getName}")
      val (scheduler, schedDown) =
        IORuntime.createDefaultScheduler(threadPrefix = s"io-scheduler-${getClass.getName}")
      val (compute, compDown) =
        IORuntime.createDefaultComputeThreadPool(
          rt,
          threadPrefix = s"io-compute-${getClass.getName}")

      new IORuntime(
        compute,
        blocking,
        scheduler,
        { () =>
          compDown()
          blockDown()
          schedDown()
        },
        IORuntimeConfig()
      )
    }

    rt
  }

  "ThreadSafeHashtable" should {
    "work correctly in the presence of many unsafeRuns" in real {
      val iterations = 100000

      object Boom extends RuntimeException("Boom!")

      def io(n: Int): IO[Unit] =
        (n % 3) match {
          case 0 => IO.unit
          case 1 => IO.canceled
          case 2 => IO.raiseError[Unit](Boom)
        }

      Resource.make(IO(hashtableRuntime()))(rt => IO(rt.shutdown())).use { rt =>
        IO(new CountDownLatch(iterations)).flatMap { counter =>
          (0 until iterations)
            .toList
            .traverse { n => IO(io(n).unsafeRunAsync { _ => counter.countDown() }(rt)) }
            .flatMap { _ => IO.blocking(counter.await()) }
            .flatMap { _ =>
              IO.blocking {
                rt.fiberErrorCbs.synchronized {
                  rt.fiberErrorCbs.hashtable.forall(_ eq null) mustEqual true
                }
              }
            }
        }
      }
    }
  }
}
