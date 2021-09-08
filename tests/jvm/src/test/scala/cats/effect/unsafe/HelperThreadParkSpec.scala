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

import cats.syntax.all._

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration._

class HelperThreadParkSpec extends BaseSpec {

  "HelperThread" should {
    "not give up when fibers are late" in real {
      def smallRuntime(): IORuntime = {
        lazy val rt: IORuntime = {
          val (blocking, blockDown) =
            IORuntime.createDefaultBlockingExecutionContext(threadPrefix =
              s"io-blocking-${getClass.getName}")
          val (scheduler, schedDown) =
            IORuntime.createDefaultScheduler(threadPrefix = s"io-scheduler-${getClass.getName}")
          val (compute, compDown) =
            IORuntime.createDefaultComputeThreadPool(
              rt,
              threadPrefix = s"io-compute-${getClass.getName}",
              threads = 2)

          IORuntime(
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

      Resource.make(IO(smallRuntime()))(rt => IO(rt.shutdown())).use { rt =>
        val io = for {
          p <- IO(Promise[Unit]())
          _ <- (IO.sleep(50.millis) *> IO(
            rt.scheduler.sleep(100.millis, () => p.success(())))).start
          _ <- IO(Await.result(p.future, Duration.Inf))
        } yield ()

        List
          .fill(10)(io.start)
          .sequence
          .flatMap(_.traverse(_.joinWithNever))
          .evalOn(rt.compute) >> IO(ok)
      }
    }
  }
}
