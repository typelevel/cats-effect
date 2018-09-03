/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
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

package cats
package effect

import cats.effect.internals.{IOAppPlatform, TestUtils, TrampolineEC}
import java.util.concurrent.{Executors, ScheduledExecutorService}
import org.scalatest.{AsyncFunSuite, BeforeAndAfterAll, Matchers}
import scala.concurrent.ExecutionContext

class IOAppTests extends AsyncFunSuite with Matchers with BeforeAndAfterAll with TestUtils {
  val trampoline: Resource[SyncIO, ExecutionContext] = Resource.pure(TrampolineEC.immediate)
  val scheduler: Resource[SyncIO, ScheduledExecutorService] =
    Resource.make(SyncIO(Executors.newScheduledThreadPool(2)))(e => SyncIO(e.shutdown()))

  test("exits with specified code") {
    IOAppPlatform.main(Array.empty, trampoline, scheduler) { (_, _) =>
      IO.pure(ExitCode(42))
    } shouldEqual 42
  }

  test("accepts arguments") {
    IOAppPlatform.main(Array("1", "2", "3"), trampoline, scheduler) { (_, args) =>
      IO.pure(ExitCode(args.mkString.toInt))
    } shouldEqual 123
  }

  test("raised error exits with 1") {
    silenceSystemErr {
      IOAppPlatform.main(Array.empty, trampoline, scheduler) { (_, _) =>
        IO.raiseError(new Exception())
      } shouldEqual 1
    }
  }

  test("synchronously brackets the executor resource") {
    var closed = false
    val resource = trampoline.flatMap { ec =>
      Resource.make(SyncIO.pure(ec))(_ => SyncIO { closed = true })
    }
    IOAppPlatform.main(Array.empty, resource, scheduler) { (runtime, _) =>
      IO(if (closed) ExitCode(0) else ExitCode(1))
    } shouldEqual 1
    closed shouldEqual true
  }

  test("synchronously brackets the scheduler resource") {
    var closed = false
    val resource = scheduler.flatMap { ec =>
      Resource.make(SyncIO.pure(ec))(_ => SyncIO { closed = true })
    }
    IOAppPlatform.main(Array.empty, trampoline, resource) { (runtime, _) =>
      IO(if (closed) ExitCode(0) else ExitCode(1))
    } shouldEqual 1
    closed shouldEqual true
  }

  test("throws if resource acquisition fails") {
    class Boom extends Exception
    silenceSystemErr {
      val resource: Resource[SyncIO, ExecutionContext] =
        Resource.liftF(SyncIO.raiseError(new Boom()))
      a [Boom] should be thrownBy {
        IOAppPlatform.main(Array.empty, resource, scheduler) { (_, _) =>
          IO.pure(ExitCode(0))
        }
      }
    }
  }
}
