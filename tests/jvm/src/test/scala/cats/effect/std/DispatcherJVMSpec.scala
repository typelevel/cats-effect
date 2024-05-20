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
package std

import cats.effect.kernel.Deferred
import cats.syntax.all._

class DispatcherJVMSpec extends BaseSpec {

  "async dispatcher" should {
    "run multiple IOs in parallel with blocking threads" in real {
      val num = 100

      for {
        latches <- (0 until num).toList.traverse(_ => Deferred[IO, Unit])
        awaitAll = latches.parTraverse_(_.get)

        // engineer a deadlock: all subjects must be run in parallel or this will hang
        subjects = latches.map(latch => latch.complete(()) >> awaitAll)

        _ <- {
          val rec = Dispatcher.parallel[IO](await = false) flatMap { runner =>
            Resource.eval(subjects.parTraverse_(act => IO(runner.unsafeRunSync(act))))
          }

          rec.use(_ => IO.unit)
        }
      } yield ok
    }
  }
}
