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

import cats.effect.std.CountDownLatch
import cats.syntax.all._

import org.scalajs.macrotaskexecutor.MacrotaskExecutor

import scala.concurrent.duration._

class BatchingMacrotaskExecutorSpec extends BaseSpec {

  "BatchingMacrotaskExecutor" should {
    "batch fibers" in real { // fails if running on MacrotaskExecutor
      CountDownLatch[IO](10).flatMap { latch =>
        IO.ref(List.empty[Int]).flatMap { ref =>
          List.range(0, 10).traverse_ { i =>
            val task = ref.update(_ :+ i) *> latch.release
            val taskOnEc = if (i == 0) task.evalOn(MacrotaskExecutor) else task
            taskOnEc.start
          } *>
            latch.await *>
            ref.get.map(_ must beEqualTo(List.range(1, 10) :+ 0))
        }
      }
    }

    "cede to macrotasks" in real { // fails if running on Promises EC
      IO.ref(false)
        .flatMap { ref =>
          ref.set(true).evalOn(MacrotaskExecutor).start *>
            (ref.get, IO.cede, ref.get).tupled.start
        }
        .flatMap { f =>
          f.join.flatMap(_.embedNever).flatMap {
            case (before, (), after) =>
              IO {
                before must beFalse
                after must beTrue
              }
          }
        }
    }

    "limit batch sizes" in real {
      IO.ref(true).flatMap { continue =>
        def go: IO[Unit] = continue.get.flatMap {
          IO.defer(go).both(IO.defer(go)).void.whenA(_)
        }
        val stop = IO.sleep(100.millis) *> continue.set(false)
        go.both(stop) *> IO(true must beTrue)
      }
    }
  }

}
